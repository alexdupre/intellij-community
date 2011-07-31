/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.repo;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.util.Processor;
import git4idea.GitBranch;
import git4idea.merge.GitMergeUtil;
import git4idea.rebase.GitRebaseUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads information about the Git repository from Git service files located in the {@code .git} folder.
 * NB: works with {@link java.io.File}, i.e. reads from disk. Consider using caching.
 * Throws a {@link GitRepoStateException} in the case of incorrect Git file format.
 * @author Kirill Likhodedov
 */
class GitRepositoryReader {

  private static final Logger LOG = Logger.getInstance(GitRepositoryReader.class);

  private static Pattern BRANCH_PATTERN          = Pattern.compile("ref: refs/heads/(\\S+)"); // branch reference in .git/HEAD
  // this format shouldn't appear, but we don't want to fail because of a space
  private static Pattern BRANCH_WEAK_PATTERN     = Pattern.compile(" *(ref:)? */?refs/heads/(\\S+)");
  private static Pattern COMMIT_PATTERN          = Pattern.compile("[0-9a-fA-F]+"); // commit hash
  private static Pattern PACKED_REFS_BRANCH_LINE = Pattern.compile("([0-9a-fA-F]+) (\\S+)"); // branch reference in .git/packed-refs
  private static Pattern PACKED_REFS_TAGREF_LINE = Pattern.compile("\\^[0-9a-fA-F]+"); // tag reference in .git/packed-refs

  private static final String REFS_HEADS_PREFIX = "refs/heads/";
  private static final int    IO_RETRIES        = 3; // number of retries before fail if an IOException happens during file read.

  private final GitRepository myRepository;
  private final File          myGitDir;       // .git/
  private final File          myHeadFile;     // .git/HEAD
  private final File          myRefsHeadsDir; // .git/refs/heads/

  GitRepositoryReader(@NotNull GitRepository repository) {
    myRepository = repository;
    myGitDir = new File(VfsUtil.virtualToIoFile(myRepository.getRoot()), ".git");
    assertFileExists(myGitDir, ".git directory not found in " + myRepository.getRoot());
    myHeadFile = new File(myGitDir, "HEAD");
    assertFileExists(myHeadFile, ".git/HEAD file not found in " + myRepository.getRoot());
    myRefsHeadsDir = new File(new File(myGitDir, "refs"), "heads");
  }

  @NotNull
  GitRepository.State readState() {
    if (isMergeInProgress()) {
      return GitRepository.State.MERGING;
    }
    if (isRebaseInProgress()) {
      return GitRepository.State.REBASING;
    }
    Head head = readHead();
    if (!head.isBranch) {
      return GitRepository.State.DETACHED;
    }
    return GitRepository.State.NORMAL;
  }

  /**
   * Finds current revision value.
   * @return The current revision hash, or <b>{@code null}</b> if current revision is unknown - it is the initial repository state.
   */
  @Nullable
  String readCurrentRevision() {
    final Head head = readHead();
    if (!head.isBranch) { // .git/HEAD is a commit
      return head.ref;
    }

    // look in /refs/heads/<branch name>
    File branchFile = null;
    for (Map.Entry<String, File> entry : getLocalBranches().entrySet()) {
      if (entry.getKey().equals(head.ref)) {
        branchFile = entry.getValue();
      }
    }
    if (branchFile != null) {
      return readBranchFile(branchFile);
    }

    // finally look in packed-refs
    return findBranchRevisionInPackedRefs(head.ref);
  }

  /**
   * If the repository is on branch, returns the current branch
   * If the repository is being rebased, returns the branch being rebased.
   * In other cases of the detached HEAD returns {@code null}.
   */
  @Nullable
  GitBranch readCurrentBranch() {
    Head head = readHead();
    if (head.isBranch) {
      return new GitBranch(head.ref, true, false);
    }
    if (isRebaseInProgress()) {
      GitBranch branch = readRebaseBranch("rebase-apply");
      if (branch == null) {
        branch = readRebaseBranch("rebase-merge");
      }
      return branch;
    }
    return null;
  }

  /**
   * Reads {@code .git/rebase-apply/head-name} or {@code .git/rebase-merge/head-name} to find out the branch which is currently being rebased,
   * and returns the {@link GitBranch} for the branch name written there, or null if these files don't exist.
   */
  @Nullable
  private GitBranch readRebaseBranch(String rebaseDirName) {
    File rebaseDir = new File(myGitDir, rebaseDirName);
    if (!rebaseDir.exists()) {
      return null;
    }
    final File headName = new File(rebaseDir, "head-name");
    if (!headName.exists()) {
      return null;
    }
    String branchName = tryLoadFile(headName).trim();
    if (branchName.startsWith(REFS_HEADS_PREFIX)) {
      branchName = branchName.substring(REFS_HEADS_PREFIX.length());
    }
    return new GitBranch(branchName, true, false);
  }

  private boolean isMergeInProgress() {
    return GitMergeUtil.isMergeInProgress(myRepository.getRoot());
  }

  private boolean isRebaseInProgress() {
    return GitRebaseUtils.isRebaseInTheProgress(myRepository.getRoot());
  }

  /**
   * Reads the {@code .git/packed-refs} file and tries to find the revision hash for the given reference (branch actually).
   * @param ref short name of the reference to find. For example, {@code master}.
   * @return commit hash, or {@code null} if the given ref wasn't found in {@code packed-refs}
   */
  @Nullable
  private String findBranchRevisionInPackedRefs(final String ref) {
    final File packedRefs = new File(myGitDir, "packed-refs");
    if (!packedRefs.exists()) {
      return null;
    }

    return tryOrThrow(new Callable<String>() {
      @Override
      public String call() throws Exception {
        BufferedReader reader = null;
        try {
          reader = new BufferedReader(new FileReader(packedRefs));
          String line;
          while ((line = reader.readLine()) != null) {
            String hash = findRefHashInPackedRefsLine(line, ref);
            if (hash != null) {
              return hash;
            }
          }
          return null;
        }
        finally {
          if (reader != null) {
            reader.close();
          }
        }
      }
    }, packedRefs);
  }

  /**
   * @return the list of local branches in this Git repository.
   *         key is the branch name, value is the file.
   */
  private Map<String, File> getLocalBranches() {
    final Map<String, File> branches = new HashMap<String, File>();
    FileUtil.processFilesRecursively(myRefsHeadsDir, new Processor<File>() {
      @Override
      public boolean process(File file) {
        if (!file.isDirectory()) {
          branches.put(FileUtil.getRelativePath(myRefsHeadsDir, file), file);
        }
        return true;
      }
    });
    return branches;
  }

  @Nullable
  private static String findRefHashInPackedRefsLine(String line, String ref) {
    if (line.startsWith("#")) { // ignoring comments
      return null;
    }
    if (PACKED_REFS_TAGREF_LINE.matcher(line).matches()) { // ignoring the hash which an annotated tag above points to
      return null;
    }
    Matcher matcher = PACKED_REFS_BRANCH_LINE.matcher(line);
    if (matcher.matches()) {
      String hash = matcher.group(1);
      String branch = matcher.group(2);
      if (branch.endsWith(ref)) {
        return hash;
      }
    } else {
      LOG.info("Ignoring invalid packed-refs line: [" + line + "]");
      return null;
    }
    return null;
  }

  private static String readBranchFile(File branchFile) {
    String rev = tryLoadFile(branchFile);
    return rev.trim();
  }

  private static void assertFileExists(File file, String message) {
    if (!file.exists()) {
      throw new GitRepoStateException(message);
    }
  }

  private Head readHead() {
    String headContent = tryLoadFile(myHeadFile);
    headContent = headContent.trim(); // remove possible leading and trailing spaces to clearly match regexps

    Matcher matcher = BRANCH_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      return new Head(true, matcher.group(1));
    }

    if (COMMIT_PATTERN.matcher(headContent).matches()) {
      return new Head(false, headContent);
    }
    matcher = BRANCH_WEAK_PATTERN.matcher(headContent);
    if (matcher.matches()) {
      LOG.info(".git/HEAD has not standard format: [" + headContent + "]. We've parsed branch [" + matcher.group(1) + "]");
      return new Head(true, matcher.group(1));
    }
    throw new GitRepoStateException("Invalid format of the .git/HEAD file: \n" + headContent);
  }

  private static String tryLoadFile(final File file) {
    return tryOrThrow(new Callable<String>() {
      @Override
      public String call() throws Exception {
        return FileUtil.loadFile(file);
      }
    }, file);
  }

  /**
   * Tries to execute the given action.
   * If an IOException happens, tries again up to 3 times, and then throws a {@link GitRepoStateException}.
   * If an other exception happens, rethrows it as a {@link GitRepoStateException}.
   * In the case of success returns the result of the task execution.
   */
  private static String tryOrThrow(Callable<String> actionToTry, File fileToLoad) {
    IOException cause = null;
    for (int i = 0; i < IO_RETRIES; i++) {
      try {
        return actionToTry.call();
      } catch (IOException e) {
        LOG.info("IOException while loading " + fileToLoad, e);
        cause = e;
      } catch (Exception e) {    // this shouldn't happen since only IOExceptions are thrown in clients.
        throw new GitRepoStateException("Couldn't load file " + fileToLoad, e);
      }
    }
    throw new GitRepoStateException("Couldn't load file " + fileToLoad, cause);
  }

  private static class Head {
    private final String ref;
    private final boolean isBranch;

    Head(boolean branch, String ref) {
      isBranch = branch;
      this.ref = ref;
    }

  }

}

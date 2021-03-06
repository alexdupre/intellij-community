/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.refactoring.typeMigration;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.typeMigration.ui.FailedConversionsDialog;
import com.intellij.refactoring.typeMigration.ui.MigrationPanel;
import com.intellij.refactoring.typeMigration.usageInfo.TypeMigrationUsageInfo;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.ui.content.Content;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewManager;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.intellij.util.ObjectUtils.assertNotNull;

public class TypeMigrationProcessor extends BaseRefactoringProcessor {
  private final static int MAX_ROOT_IN_PREVIEW_PRESENTATION = 3;

  private PsiElement[] myRoot;
  private Function<PsiElement, PsiType> myRootTypes;
  private final TypeMigrationRules myRules;
  private TypeMigrationLabeler myLabeler;

  public TypeMigrationProcessor(final Project project, final PsiElement[] roots, final Function<PsiElement, PsiType> rootTypes, final TypeMigrationRules rules) {
    super(project);
    myRoot = roots;
    myRules = rules;
    myRootTypes = rootTypes;
  }

  public static void runHighlightingTypeMigration(final Project project,
                                                  final Editor editor,
                                                  final TypeMigrationRules rules,
                                                  final PsiElement root,
                                                  final PsiType migrationType) {
    runHighlightingTypeMigration(project, editor, rules, root, migrationType, false);
  }

  public static void runHighlightingTypeMigration(final Project project,
                                                  final Editor editor,
                                                  final TypeMigrationRules rules,
                                                  final PsiElement root,
                                                  final PsiType migrationType,
                                                  final boolean optimizeImports) {
    runHighlightingTypeMigration(project, editor, rules, new PsiElement[] {root}, Functions.<PsiElement, PsiType>constant(migrationType), optimizeImports);
  }


  public static void runHighlightingTypeMigration(final Project project,
                                                  final Editor editor,
                                                  final TypeMigrationRules rules,
                                                  final PsiElement[] roots,
                                                  final Function<PsiElement, PsiType> migrationTypeFunction,
                                                  final boolean optimizeImports) {
    final Set<PsiFile> containingFiles = ContainerUtil.map2Set(roots, new Function<PsiElement, PsiFile>() {
      @Override
      public PsiFile fun(PsiElement element) {
        return element.getContainingFile();
      }
    });
    final TypeMigrationProcessor processor = new TypeMigrationProcessor(project, roots, migrationTypeFunction, rules) {
      @Override
      public void performRefactoring(@NotNull final UsageInfo[] usages) {
        super.performRefactoring(usages);
        if (editor != null) {
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
              final List<PsiElement> result = new ArrayList<PsiElement>();
              for (UsageInfo usage : usages) {
                final PsiElement element = usage.getElement();
                if (element == null || !containingFiles.contains(element.getContainingFile())) continue;
                if (element instanceof PsiMethod) {
                  result.add(((PsiMethod)element).getReturnTypeElement());
                }
                else if (element instanceof PsiVariable) {
                  result.add(((PsiVariable)element).getTypeElement());
                }
                else {
                  result.add(element);
                }
              }
              RefactoringUtil.highlightAllOccurrences(project, PsiUtilCore.toPsiElementArray(result), editor);
            }
          });
        }
        if (optimizeImports) {
          final JavaCodeStyleManager javaCodeStyleManager = JavaCodeStyleManager.getInstance(myProject);
          final Set<PsiFile> affectedFiles = new THashSet<PsiFile>();
          for (UsageInfo usage : usages) {
            final PsiFile usageFile = usage.getFile();
            if (usageFile != null) {
              affectedFiles.add(usageFile);
            }
          }
          for (PsiFile file : affectedFiles) {
            javaCodeStyleManager.optimizeImports(file);
            javaCodeStyleManager.shortenClassReferences(file);
          }
        }
      }
    };
    processor.run();
  }


  @NotNull
  @Override
  protected UsageViewDescriptor createUsageViewDescriptor(@NotNull UsageInfo[] usages) {
    return new TypeMigrationViewDescriptor(myRoot[0]);
  }

  @Override
  protected boolean preprocessUsages(@NotNull Ref<UsageInfo[]> refUsages) {
    if (hasFailedConversions()) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        throw new RuntimeException(StringUtil.join(myLabeler.getFailedConversionsReport(), "\n"));
      }
      FailedConversionsDialog dialog = new FailedConversionsDialog(myLabeler.getFailedConversionsReport(), myProject);
      if (!dialog.showAndGet()) {
        final int exitCode = dialog.getExitCode();
        prepareSuccessful();
        if (exitCode == FailedConversionsDialog.VIEW_USAGES_EXIT_CODE) {
          previewRefactoring(refUsages.get());
        }
        return false;
      }
    }
    prepareSuccessful();
    return true;
  }

  public boolean hasFailedConversions() {
    return myLabeler.hasFailedConversions();
  }

  @Override
  protected void previewRefactoring(@NotNull final UsageInfo[] usages) {
    MigrationPanel panel = new MigrationPanel(myRoot, myLabeler, myProject, isPreviewUsages());
    String name;
    if (myRoot.length == 1) {
      String fromType = assertNotNull(TypeMigrationLabeler.getElementType(myRoot[0])).getPresentableText();
      String toType = myRootTypes.fun(myRoot[0]).getPresentableText();
      String text;
      text = getPresentation(myRoot[0]);
      name = "Migrate Type of " + text + " from \'" + fromType + "\' to \'" + toType + "\'";
    } else {
      final int rootsInPresentationCount = myRoot.length > MAX_ROOT_IN_PREVIEW_PRESENTATION ? MAX_ROOT_IN_PREVIEW_PRESENTATION : myRoot.length;
      String[] rootsPresentation = new String[rootsInPresentationCount];
      for (int i = 0; i < rootsInPresentationCount; i++) {
        final PsiElement root = myRoot[i];
        rootsPresentation[i] = root instanceof PsiNamedElement ? ((PsiNamedElement)root).getName() : root.getText();
      }
      rootsPresentation = StringUtil.surround(rootsPresentation, "\'", "\'");
      name = "Migrate Type of " + StringUtil.join(rootsPresentation, ", ");
      if (myRoot.length > MAX_ROOT_IN_PREVIEW_PRESENTATION) {
        name += "...";
      }
    }
    Content content = UsageViewManager.getInstance(myProject).addContent(name, false, panel, true, true);
    panel.setContent(content);
    ToolWindowManager.getInstance(myProject).getToolWindow(ToolWindowId.FIND).activate(null);
  }

  public static String getPresentation(PsiElement element) {
    String text;
    if (element instanceof PsiField) {
      text = "field \'" + ((PsiField)element).getName() + "\'";
    }
    else if (element instanceof PsiParameter) {
      text = "parameter \'" + ((PsiParameter)element).getName() + "\'";
    }
    else if (element instanceof PsiLocalVariable) {
      text = "variable \'" + ((PsiLocalVariable)element).getName() + "\'";
    }
    else if (element instanceof PsiMethod) {
      text = "method \'" + ((PsiMethod)element).getName() + "\' return";
    }
    else {
      text = element.getText();
    }
    return text;
  }

  @NotNull
  @Override
  public UsageInfo[] findUsages() {
    myLabeler = new TypeMigrationLabeler(myRules, myRootTypes);

    try {
      return myLabeler.getMigratedUsages(!isPreviewUsages(), myRoot);
    }
    catch (TypeMigrationLabeler.MigrateException e) {
      setPreviewUsages(true);
      myLabeler.clearStopException();
      return myLabeler.getMigratedUsages(false, myRoot);
    }
  }

  @Override
  protected void refreshElements(@NotNull PsiElement[] elements) {
    myRoot = elements;
  }

  @Override
  public void performRefactoring(@NotNull UsageInfo[] usages) {
    for (PsiElement element : myRoot) {
      if (element instanceof PsiVariable && ((PsiVariable)element).getTypeElement() != null) {
        ((PsiVariable)element).normalizeDeclaration();
      }
    }
    change(myLabeler, usages);
  }

  public static void change(TypeMigrationLabeler labeler, UsageInfo[] usages) {
    final List<PsiNewExpression> newExpressionsToCheckDiamonds = new SmartList<PsiNewExpression>();

    List<UsageInfo> nonCodeUsages = new ArrayList<UsageInfo>();
    for (UsageInfo usage : usages) {
      if (((TypeMigrationUsageInfo)usage).isExcluded()) continue;
      final PsiElement element = usage.getElement();
      if (element instanceof PsiVariable ||
          element instanceof PsiMember ||
          element instanceof PsiExpression ||
          element instanceof PsiReferenceParameterList) {
        labeler.change((TypeMigrationUsageInfo)usage, new Consumer<PsiNewExpression>() {
          @Override
          public void consume(@NotNull PsiNewExpression expression) {
            newExpressionsToCheckDiamonds.add(expression);
          }
        });
      }
      else {
        nonCodeUsages.add(usage);
      }
    }

    for (PsiNewExpression newExpression : newExpressionsToCheckDiamonds) {
      labeler.postProcessNewExpression(newExpression);
    }

    for (UsageInfo usageInfo : nonCodeUsages) {
      final PsiElement element = usageInfo.getElement();
      if (element != null) {
        final PsiReference reference = element.getReference();
        if (reference != null) {
          final Object target = labeler.getConversion(element);
          if (target instanceof PsiMember) {
            try {
              reference.bindToElement((PsiElement)target);
            }
            catch (IncorrectOperationException ignored) { }
          }
        }
      }
    }
  }

  public TypeMigrationLabeler getLabeler() {
    return myLabeler;
  }

  @Override
  protected String getCommandName() {
    return "TypeMigration";
  }
}

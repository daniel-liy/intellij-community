/*
 * User: anna
 * Date: 10-Jul-2007
 */
package com.intellij.ide.util.newProjectWizard.modes;

import com.intellij.ide.util.newProjectWizard.ProjectNameStep;
import com.intellij.ide.util.newProjectWizard.StepSequence;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

public class CreateFromScratchMode implements WizardMode {
  private StepSequence myStepSequence;
  private Map<String, ModuleBuilder> myBuildersMap = new HashMap<String, ModuleBuilder>();

  @NotNull
  public String getDisplayName(final WizardContext context) {
    return ProjectBundle.message("project.new.wizard.from.scratch.title", context.getPresentationName());
  }

  @NotNull
  public String getDescription(final WizardContext context) {
    return ProjectBundle.message("project.new.wizard.from.scratch.description", context.getPresentationName());
  }

  @NotNull
  public StepSequence getSteps(final WizardContext context, final ModulesProvider modulesProvider) {
    if (myStepSequence == null) {
      myStepSequence = new StepSequence(null);
      myStepSequence.addCommonStep(new ProjectNameStep(context, myStepSequence, this));
      final ModuleType[] allModuleTypes = ModuleTypeManager.getInstance().getRegisteredTypes();
      for (ModuleType type : allModuleTypes) {
        final StepSequence sequence = new StepSequence(myStepSequence);
        myStepSequence.addSpecificSteps(type.getId(), sequence);
        final ModuleBuilder builder = type.createModuleBuilder();
        myBuildersMap.put(type.getId(), builder);
        final ModuleWizardStep[] steps = type.createWizardSteps(context, builder, modulesProvider);
        for (ModuleWizardStep step : steps) {
          sequence.addCommonStep(step);
        }
      }
    }
    return myStepSequence;
  }

  public boolean isAvailable(WizardContext context) {
    return true;
  }

  public ModuleBuilder getModuleBuilder() {
    return myBuildersMap.get(myStepSequence.getSelectedType());
  }

  @Nullable
  public JComponent getAdditionalSettings() {
    return null;
  }

  public void onChosen(final boolean enabled) {
    
  }

  public void dispose() {
      myBuildersMap.clear();
      myStepSequence = null;
    }
}
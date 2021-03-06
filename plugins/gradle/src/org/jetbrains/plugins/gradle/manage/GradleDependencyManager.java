package org.jetbrains.plugins.gradle.manage;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.config.PlatformFacade;
import org.jetbrains.plugins.gradle.model.gradle.*;
import org.jetbrains.plugins.gradle.sync.GradleProjectStructureHelper;
import org.jetbrains.plugins.gradle.util.GradleUtil;

import java.util.*;

/**
 * Thread-safe.
 * 
 * @author Denis Zhdanov
 * @since 2/7/12 3:23 PM
 */
public class GradleDependencyManager {

  @NotNull private final PlatformFacade       myPlatformFacade;
  @NotNull private final GradleLibraryManager myLibraryManager;

  public GradleDependencyManager(@NotNull PlatformFacade platformFacade, @NotNull GradleLibraryManager manager) {
    myPlatformFacade = platformFacade;
    myLibraryManager = manager;
  }

  public void importDependency(@NotNull GradleDependency dependency, @NotNull Module module, boolean synchronous) {
    importDependencies(Collections.singleton(dependency), module, synchronous);
  }

  public void importDependencies(@NotNull Iterable<GradleDependency> dependencies, @NotNull Module module, boolean synchronous) {
    final List<GradleModuleDependency> moduleDependencies = new ArrayList<GradleModuleDependency>();
    final List<GradleLibraryDependency> libraryDependencies = new ArrayList<GradleLibraryDependency>();
    GradleEntityVisitor visitor = new GradleEntityVisitorAdapter() {
      @Override
      public void visit(@NotNull GradleModuleDependency dependency) {
        moduleDependencies.add(dependency);
      }

      @Override
      public void visit(@NotNull GradleLibraryDependency dependency) {
        libraryDependencies.add(dependency);
      }
    };
    for (GradleDependency dependency : dependencies) {
      dependency.invite(visitor);
    }
    importLibraryDependencies(libraryDependencies, module, synchronous);
    importModuleDependencies(moduleDependencies, module, synchronous);
  }

  @SuppressWarnings("MethodMayBeStatic")
  public void importModuleDependencies(@NotNull final Collection<GradleModuleDependency> dependencies,
                                       @NotNull final Module module,
                                       boolean synchronous)
  {
    if (dependencies.isEmpty()) {
      return;
    }
    
    GradleUtil.executeProjectChangeAction(module.getProject(), dependencies, synchronous, new Runnable() {
      @Override
      public void run() {
        ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        final ModifiableRootModel moduleRootModel = moduleRootManager.getModifiableModel();
        try {
          final GradleProjectStructureHelper projectStructureHelper
            = ServiceManager.getService(module.getProject(), GradleProjectStructureHelper.class);
          for (GradleModuleDependency dependency : dependencies) {
            final String moduleName = dependency.getName();
            final Module intellijModule = projectStructureHelper.findIdeModule(moduleName);
            if (intellijModule == null) {
              assert false;
              continue;
            }
            else if (intellijModule.equals(module)) {
              // Gradle api returns recursive module dependencies (a module depends on itself) for 'gradle' project.
              continue;
            }

            ModuleOrderEntry orderEntry = projectStructureHelper.findIdeModuleDependency(dependency, moduleRootModel);
            if (orderEntry == null) {
              orderEntry = moduleRootModel.addModuleOrderEntry(intellijModule);
            }
            orderEntry.setScope(dependency.getScope());
            orderEntry.setExported(dependency.isExported());
          }
        }
        finally {
          moduleRootModel.commit();
        }
      }
    });
  }
  
  public void importLibraryDependencies(@NotNull final Iterable<GradleLibraryDependency> dependencies,
                                        @NotNull final Module module,
                                        final boolean synchronous)
  {
    GradleUtil.executeProjectChangeAction(module.getProject(), dependencies, synchronous, new Runnable() {
      @Override
      public void run() {
        LibraryTable libraryTable = myPlatformFacade.getProjectLibraryTable(module.getProject());
        Set<GradleLibrary> librariesToImport = new HashSet<GradleLibrary>();
        for (GradleLibraryDependency dependency : dependencies) {
          final Library library = libraryTable.getLibraryByName(dependency.getName());
          if (library == null) {
            librariesToImport.add(dependency.getTarget());
          }
        }
        if (!librariesToImport.isEmpty()) {
          myLibraryManager.importLibraries(librariesToImport, module.getProject(), synchronous);
        }

        for (GradleLibraryDependency dependency : dependencies) {
          GradleProjectStructureHelper helper = ServiceManager.getService(module.getProject(), GradleProjectStructureHelper.class);
          ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
          final ModifiableRootModel moduleRootModel = moduleRootManager.getModifiableModel();
          try {
            libraryTable = myPlatformFacade.getProjectLibraryTable(module.getProject());
            final Library library = libraryTable.getLibraryByName(dependency.getName());
            if (library == null) {
              assert false;
              continue;
            }
            LibraryOrderEntry orderEntry = helper.findIdeLibraryDependency(dependency.getName(), moduleRootModel);
            if (orderEntry == null) {
              // We need to get the most up-to-date Library object due to our project model restrictions.
              orderEntry = moduleRootModel.addLibraryEntry(library);
            }
            orderEntry.setExported(dependency.isExported());
            orderEntry.setScope(dependency.getScope());
          }
          finally {
            moduleRootModel.commit();
          }
        }
      }
    });
  }

  public void removeDependency(@NotNull final ExportableOrderEntry dependency, boolean synchronous) {
    removeDependencies(Collections.singleton(dependency), synchronous);
  }
  
  @SuppressWarnings("MethodMayBeStatic")
  public void removeDependencies(@NotNull final Collection<? extends ExportableOrderEntry> dependencies, boolean synchronous) {
    if (dependencies.isEmpty()) {
      return;
    }

    for (final ExportableOrderEntry dependency : dependencies) {
      final Module module = dependency.getOwnerModule();
      GradleUtil.executeProjectChangeAction(module.getProject(), dependency, synchronous, new Runnable() {
        @Override
        public void run() {
          ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
          final ModifiableRootModel moduleRootModel = moduleRootManager.getModifiableModel();
          try {
            // The thing is that intellij created order entry objects every time new modifiable model is created,
            // that's why we can't use target dependency object as is but need to get a reference to the current
            // entry object from the model instead.
            for (OrderEntry entry : moduleRootModel.getOrderEntries()) {
              if (entry.getPresentableName().equals(dependency.getPresentableName())) {
                moduleRootModel.removeOrderEntry(entry);
                break;
              }
            }
          }
          finally {
            moduleRootModel.commit();
          } 
        }
      });
    }
  }

  @SuppressWarnings("MethodMayBeStatic")
  public void setScope(@NotNull final DependencyScope scope, @NotNull final ExportableOrderEntry dependency, boolean synchronous) {
    Project project = dependency.getOwnerModule().getProject();
    GradleUtil.executeProjectChangeAction(project, dependency, synchronous, new Runnable() {
      @Override
      public void run() {
        doForDependency(dependency, new Consumer<ExportableOrderEntry>() {
          @Override
          public void consume(ExportableOrderEntry entry) {
            entry.setScope(scope);
          }
        });
      }
    });
  }

  @SuppressWarnings("MethodMayBeStatic")
  public void setExported(final boolean exported, @NotNull final ExportableOrderEntry dependency, boolean synchronous) {
    Project project = dependency.getOwnerModule().getProject();
    GradleUtil.executeProjectChangeAction(project, dependency, synchronous, new Runnable() {
      @Override
      public void run() {
        doForDependency(dependency, new Consumer<ExportableOrderEntry>() {
          @Override
          public void consume(ExportableOrderEntry entry) {
            entry.setExported(exported);
          }
        });
      }
    });
  }

  private static void doForDependency(@NotNull ExportableOrderEntry entry, @NotNull Consumer<ExportableOrderEntry> consumer) {
    // We need to get an up-to-date modifiable model to work with.
    ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(entry.getOwnerModule());
    final ModifiableRootModel moduleRootModel = moduleRootManager.getModifiableModel();
    try {
      // The thing is that intellij created order entry objects every time new modifiable model is created,
      // that's why we can't use target dependency object as is but need to get a reference to the current
      // entry object from the model instead.
      for (OrderEntry e : moduleRootModel.getOrderEntries()) {
        if (e instanceof ExportableOrderEntry && e.getPresentableName().equals(entry.getPresentableName())) {
          consumer.consume((ExportableOrderEntry)e);
          break;
        }
      }
    }
    finally {
      moduleRootModel.commit();
    }
  }
}

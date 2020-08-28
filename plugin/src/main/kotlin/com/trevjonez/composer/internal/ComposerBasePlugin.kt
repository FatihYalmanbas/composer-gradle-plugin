/*
 *    Copyright 2018 Trevor Jones
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.trevjonez.composer.internal

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.TestedVariant
import com.trevjonez.composer.ComposerPlugin
import com.trevjonez.composer.ComposerTask
import com.trevjonez.composer.ConfigExtension
import com.trevjonez.composer.ConfiguratorDomainObj
import com.trevjonez.composer.composerConfig
import org.gradle.api.DomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File

abstract class ComposerBasePlugin<T> : Plugin<Project>
    where T : BaseVariant, T : TestedVariant {

  abstract val sdkDir: File
  abstract val testableVariants: DomainObjectCollection<T>

  abstract fun T.getApk(task: ComposerTask): Provider<RegularFile>
  abstract fun T.getTestApk(task: ComposerTask): Provider<RegularFile>
  abstract fun T.getExtraApks(task: ComposerTask): ConfigurableFileCollection
  abstract fun T.getMultiApks(task: ComposerTask): ConfigurableFileCollection

  open fun T.getOutputDir(task: ComposerTask): Provider<Directory> {
    return project.layout.buildDirectory.dir("reports/composer/$dirName")
  }

  open fun T.isTestable(): Boolean {
    return testVariant != null
  }

  lateinit var project: Project
  lateinit var globalConfig: ConfigExtension

  override fun apply(target: Project) {
    this.project = target
    target.composerConfig()
    globalConfig = target.extensions.create(ConfigExtension.DEFAULT_NAME, target)
    target.afterEvaluate { observeVariants() }
  }

  private fun observeVariants() {
    testableVariants.all {
      val variant = this
      if (globalConfig.variants.isEmpty() || globalConfig.variants.contains(name)) {
        if (!isTestable()) {
          project.logger.info("variant: ${name}. is not testable. skipping composer task registration.")
          return@all
        }

        project.tasks.register<ComposerTask>("test${name.capitalize()}Composer") {
          group = ComposerPlugin.GROUP
          description = "Run composer for ${variant.name} variant"
          environment("ANDROID_HOME", sdkDir.absolutePath)

          val variantConfigurator = globalConfig.configs.findByName(variant.name)
          if (variantConfigurator == null)
            project.logger.info("ComposerBasePlugin: Variant configurator for `${variant.name}` is null")

          variant.configureTaskDslLevelProperties(this, variantConfigurator)
          configureGlobalDslLevelProperties(this, variantConfigurator)
        }
      }
    }
  }

  private fun T.configureTaskDslLevelProperties(
      composerTask: ComposerTask,
      variantConfigurator: ConfiguratorDomainObj?
  ) {

    composerTask.apk.set(
        variantConfigurator?.apk?.takeIf { it.isPresent }
        ?: getApk(composerTask)
    )

    composerTask.testApk.set(
        variantConfigurator?.testApk?.takeIf { it.isPresent }
        ?: getTestApk(composerTask)
    )

    composerTask.outputDir.set(
        variantConfigurator?.outputDir?.takeIf { it.isPresent }
        ?: getOutputDir(composerTask)
    )

    composerTask.extraApks.setFrom(
        variantConfigurator?.extraApks?.takeUnless { it.isEmpty }
        ?: getExtraApks(composerTask)
    )

    composerTask.multiApks.setFrom(
        variantConfigurator?.multiApks?.takeUnless { it.isEmpty }
        ?: getMultiApks(composerTask)
    )
  }

  private fun logDslSelection(propertyName: String, source: String) {
    project.logger.info("ComposerBasePlugin: Selecting `$propertyName` from $source config.")
  }

  private fun configureGlobalDslLevelProperties(
      composerTask: ComposerTask,
      variantConfigurator: ConfiguratorDomainObj?
  ) {
    if (globalConfig.withOrchestrator.isPresent) {
      logDslSelection("withOrchestrator", "global")
      composerTask.withOrchestrator(globalConfig.withOrchestrator)
    }

    if (variantConfigurator?.withOrchestrator?.isPresent == true) {
      logDslSelection("withOrchestrator", "variant")
      composerTask.withOrchestrator(variantConfigurator.withOrchestrator)
    }

    if (globalConfig.shard.isPresent) {
      logDslSelection("shard", "global")
      composerTask.shard(globalConfig.shard)
    }

    if (variantConfigurator?.shard?.isPresent == true) {
      logDslSelection("shard", "variant")
      composerTask.shard(variantConfigurator.shard)
    }

    if (globalConfig.instrumentationArguments.isPresent) {
      logDslSelection("instrumentationArguments", "global")
      composerTask.instrumentationArguments(globalConfig.instrumentationArguments)
    }

    if (variantConfigurator?.instrumentationArguments?.isPresent == true) {
      logDslSelection("instrumentationArguments", "variant")
      composerTask.instrumentationArguments(variantConfigurator.instrumentationArguments)
    }

    if (globalConfig.verboseOutput.isPresent) {
      logDslSelection("verboseOutput", "global")
      composerTask.verboseOutput(globalConfig.verboseOutput)
    }

    if (variantConfigurator?.verboseOutput?.isPresent == true) {
      logDslSelection("verboseOutput", "variant")
      composerTask.verboseOutput(variantConfigurator.verboseOutput)
    }

    if (variantConfigurator?.devices?.isPresent == true) {
      logDslSelection("devices", "variant")
      composerTask.devices(variantConfigurator.devices)
    }

    if (globalConfig.devices.isPresent) {
      logDslSelection("devices", "global")
      composerTask.devices(globalConfig.devices)
    }

    if (globalConfig.devicePattern.isPresent) {
      logDslSelection("devicePattern", "global")
      composerTask.devicePattern(globalConfig.devicePattern)
    }

    if (variantConfigurator?.devicePattern?.isPresent == true) {
      logDslSelection("devicePattern", "variant")
      composerTask.devicePattern(variantConfigurator.devicePattern)
    }

    if (globalConfig.keepOutput.isPresent) {
      logDslSelection("keepOutput", "global")
      composerTask.keepOutput(globalConfig.keepOutput)
    }

    if (variantConfigurator?.keepOutput?.isPresent == true) {
      logDslSelection("keepOutput", "variant")
      composerTask.keepOutput(variantConfigurator.keepOutput)
    }

    if (globalConfig.apkInstallTimeout.isPresent) {
      logDslSelection("apkInstallTimeout", "global")
      composerTask.apkInstallTimeout(globalConfig.apkInstallTimeout)
    }

    if (variantConfigurator?.apkInstallTimeout?.isPresent == true) {
      logDslSelection("apkInstallTimeout", "variant")
      composerTask.apkInstallTimeout(variantConfigurator.apkInstallTimeout)
    }
  }
}

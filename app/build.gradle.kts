/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import com.android.ddmlib.AndroidDebugBridge
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
}

protobuf {
  protoc {
    val protocDep: MinimalExternalModuleDependency = libs.protobuf.protoc.get()
    artifact = "${protocDep.group}:${protocDep.name}:${protocDep.version}"
  }

  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        id("kotlin")
        id("java") {}
      }
    }
  }
}

val (projectVersionName, projectVersionCode) = readVersionProperties(project)

android {
  namespace = "org.tfv.deskflow"
  buildToolsVersion = "36.0.0"
  compileSdk = 36

  defaultConfig {
    applicationId = "org.tfv.deskflow"
    minSdk = 34

    versionCode = projectVersionCode
    versionName = projectVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables.useSupportLibrary = true
  }
  buildFeatures {
    buildConfig = true
    compose = true
    viewBinding = true
    aidl = true
  }

  buildTypes {
    debug {
      isDebuggable = true
      isMinifyEnabled = false
      buildConfigField("boolean", "DEBUG", "true")
    }
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      buildConfigField("boolean", "DEBUG", "false")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  dependenciesInfo {
    includeInApk = true
    includeInBundle = true
  }

  sourceSets { getByName("main") { proto { srcDir("src/main/proto") } } }

  lint { disable.add("NullSafeMutableLiveData") }

  packaging {
    resources {
      // BouncyCastle ships multi-release JARs whose OSGI manifests collide;
      // none are needed at runtime.
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
      excludes += "META-INF/INDEX.LIST"
      excludes += "META-INF/*.SF"
      excludes += "META-INF/*.DSA"
      excludes += "META-INF/*.RSA"
    }
  }
}

dependencies {
  implementation(project(":client")) { exclude(group = "io.github.oshai") }

  implementation(libs.arrow.core)
  implementation(libs.arrow.fx.coroutines)
  implementation(project(":iconics-typeface-library"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.logging.android)
  implementation(libs.slf4j.api)
  implementation(libs.protobuf.java.runtime)
  implementation(libs.protobuf.java.util)

  implementation(libs.protobuf.kotlin)

  implementation(libs.google.accompanist.permissions)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.normal)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.android)
  implementation(libs.androidx.lifecycle.runtime.normal)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.lifecycle.livedata.normal)
  implementation(libs.androidx.lifecycle.livedata.core.normal)
  implementation(libs.androidx.lifecycle.livedata.core.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.normal)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.material3)

  implementation(libs.material)

  // BouncyCastle: X.509 self-signed client-cert generation for mTLS (PeerAuth).
  // The server requires a client certificate; AndroidKeyStore keys can't satisfy
  // Conscrypt's raw-RSA signing upcall, so we use a software keypair. Not
  // registered as a JCA provider (used only for cert building).
  implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
  implementation(libs.androidx.constraintlayout)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("installDebugEnableAccessibilityService") {
  dependsOn("installDebug")
  val ops = project.objects.newInstance<InjectedOps>()
  val execOps: ExecOperations = ops.execOps

  doLast {
    val adbPath = "${android.sdkDirectory}/platform-tools/adb"

    runCatching {
      AndroidDebugBridge.terminate()
      AndroidDebugBridge.init(true)
    }
    val adb =
      AndroidDebugBridge.createBridge(
        adbPath,
        true,
        1000L,
        TimeUnit.MILLISECONDS,
      )
    adb.startAdb(1000L, TimeUnit.MILLISECONDS)
    println("ADB server started: ${adb.isConnected}")

    require(adb.devices.isNotEmpty()) {
      "At least one device must be connected to install app & enable the AccessibilityService."
    }

    adb.devices.forEach { device ->
      println(
        "Activating AccessibilityService on Device ${device.serialNumber}"
      )
      if (!device.isOnline) {
        println("Device ${device.serialNumber} is not online. Skipping...")
        return@forEach
      }

      execOps.exec {
        commandLine(
          adbPath,
          "-s",
          device.serialNumber,
          "shell",
          "settings",
          "put",
          "secure",
          "enabled_accessibility_services",
          "\"org.tfv.deskflow/org.tfv.deskflow.services.GlobalInputService\"",
        )
      }
      execOps.exec {
        commandLine(
          adbPath,
          "-s",
          device.serialNumber,
          "shell",
          "am",
          "start",
          "-n",
          "org.tfv.deskflow/org.tfv.deskflow.ui.activities.RootActivity",
        )
      }
    }

    runCatching { AndroidDebugBridge.terminate() }
  }
}

class RenameAABAction : Action<Task> {
  override fun execute(task: Task) {
    val isBundle = task.name.startsWith("bundle")
    val isAssemble = task.name.startsWith("assembleRelease")
    if ((isAssemble || isBundle) && !task.name.contains("Rename")) {
      val (outputFolder, ext, flavor) =
        when {
          isBundle ->
            Triple(
              "bundle",
              "aab",
              task.name.substring("bundle".length).lowercase(),
            )
          else ->
            Triple(
              "apk",
              "apk",
              task.name.substring("assemble".length).lowercase(),
            )
        }
      val renameTaskName = "${task.name}Rename${ext.uppercase()}"
      val layout = project.layout
      val buildDir = layout.buildDirectory.asFile.get().absolutePath
      val bundlePath = "${buildDir}/outputs/${outputFolder}/${flavor}/"
      val readyPath =
        file("${buildDir}/outputs/${outputFolder}-ready/${flavor}/")
      val defaultAppBundleFilename = "app-${flavor}${if (isAssemble) "-unsigned" else ""}.$ext"
      val appBundleFilename = "DeskflowAndroid-v$projectVersionName.$ext"
      readyPath.mkdirs()
      
      tasks.register<Copy>(renameTaskName) {
        from(bundlePath)
        include(defaultAppBundleFilename)
        destinationDir = readyPath
        rename(defaultAppBundleFilename, appBundleFilename)
      }

      task.finalizedBy(renameTaskName)
    }
  }
}

tasks.whenTaskAdded(RenameAABAction())

@file:Suppress("MagicNumber")

package com.gojuno.composer

import com.gojuno.commander.android.AdbDevice
import com.gojuno.commander.android.adb
import com.gojuno.commander.android.deleteFolder
import com.gojuno.commander.android.log
import com.gojuno.commander.android.pullFolder
import com.gojuno.commander.android.redirectLogcatToFile
import com.gojuno.commander.os.Notification
import com.gojuno.commander.os.nanosToHumanReadableTime
import com.gojuno.commander.os.process
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function3
import io.reactivex.schedulers.Schedulers
import java.io.File

data class AdbDeviceTestRun(
    val adbDevice: AdbDevice,
    val tests: List<AdbDeviceTest>,
    val passedCount: Int,
    val ignoredCount: Int,
    val failedCount: Int,
    val durationNanos: Long,
    val timestampMillis: Long,
    val logcat: File,
    val instrumentationOutput: File
)

data class AdbDeviceTest(
    val adbDevice: AdbDevice,
    val className: String,
    val testName: String,
    val status: Status,
    val durationNanos: Long,
    val logcat: File,
    val files: List<File>,
    val screenshots: List<File>
) {
  sealed class Status {
    object Passed : Status()
    data class Ignored(val stacktrace: String) : Status()
    data class Failed(val stacktrace: String) : Status()
  }
}

@Suppress("LongParameterList", "LongMethod")
fun AdbDevice.runTests(
    testPackageName: String,
    testRunnerClass: String,
    instrumentationArguments: String,
    outputDir: File,
    verboseOutput: Boolean,
    keepOutput: Boolean,
    useTestServices: Boolean
): Single<AdbDeviceTestRun> {

  val adbDevice = this
  val logsDir = File(File(outputDir, "logs"), adbDevice.id)
  val instrumentationOutputFile = File(logsDir, "instrumentation.output")
  val commandPrefix = if (useTestServices) {
    "CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain "
  } else ""

  val runTests = process(
      commandAndArgs = listOf(
          adb.absolutePath,
          "-s",
          adbDevice.id,
          "shell",
          "${commandPrefix}am instrument -w -r $instrumentationArguments $testPackageName/$testRunnerClass"
      ),
      timeout = null,
      redirectOutputTo = instrumentationOutputFile,
      keepOutputOnExit = keepOutput,
      destroyOnUnsubscribe = true,
      print = verboseOutput
  ).share()

  @Suppress("destructure")
  val runningTests = runTests
      .ofType(Notification.Start::class.java)
      .flatMap { readInstrumentationOutput(it.output) }
      .asTests()
      .doOnNext { test ->
        val status = when (test.status) {
          is InstrumentationTest.Status.Passed -> "passed"
          is InstrumentationTest.Status.Ignored -> "ignored"
          is InstrumentationTest.Status.Failed -> "failed"
        }

        adbDevice.log(
            "Test ${test.index}/${test.total} $status in " +
            "${test.durationNanos.nanosToHumanReadableTime()}: " +
            "${test.className}.${test.testName}"
        )
      }
      .flatMap { test ->
        pullTestFiles(adbDevice, test, outputDir, verboseOutput)
            .toObservable()
            .subscribeOn(Schedulers.io())
            .map { pulledFiles -> test to pulledFiles }
      }
      .toList()

  val adbDeviceTestRun = Single.zip(
      Single.fromCallable { System.nanoTime() },
      runningTests,
      BiFunction { time: Long, tests: List<Pair<InstrumentationTest, PulledFiles>> -> time to tests }
  )
      .map { (startTimeNanos, testsWithPulledFiles) ->
        val tests = testsWithPulledFiles.map { it.first }

        AdbDeviceTestRun(
            adbDevice = adbDevice,
            tests = testsWithPulledFiles.map { (test, pulledFiles) ->
              AdbDeviceTest(
                  adbDevice = adbDevice,
                  className = test.className,
                  testName = test.testName,
                  status = when (test.status) {
                    is InstrumentationTest.Status.Passed -> AdbDeviceTest.Status.Passed
                    is InstrumentationTest.Status.Ignored -> AdbDeviceTest.Status.Ignored(test.status.stacktrace)
                    is InstrumentationTest.Status.Failed -> AdbDeviceTest.Status.Failed(test.status.stacktrace)
                  },
                  durationNanos = test.durationNanos,
                  logcat = logcatFileForTest(logsDir, test.className, test.testName),
                  files = pulledFiles.files.sortedBy { it.name },
                  screenshots = pulledFiles.screenshots.sortedBy { it.name }
              )
            },
            passedCount = tests.count { it.status is InstrumentationTest.Status.Passed },
            ignoredCount = tests.count { it.status is InstrumentationTest.Status.Ignored },
            failedCount = tests.count { it.status is InstrumentationTest.Status.Failed },
            durationNanos = System.nanoTime() - startTimeNanos,
            timestampMillis = System.currentTimeMillis(),
            logcat = logcatFileForDevice(logsDir),
            instrumentationOutput = instrumentationOutputFile
        )
      }

  val testRunFinish = runTests.ofType(Notification.Exit::class.java).cache()

  val saveLogcat = saveLogcat(adbDevice, logsDir)
      .map { Unit }
      .takeUntil(testRunFinish)
      .last(Unit) // Default value to allow zip finish normally even if no tests were run.

  return Single.zip(
      adbDeviceTestRun,
      saveLogcat,
      testRunFinish.singleOrError(),
      Function3 { suite: AdbDeviceTestRun, _: Unit, _: Notification.Exit -> suite }
  )
      .doOnSubscribe { adbDevice.log("Starting tests...") }
      .doOnSuccess { testRun ->
        adbDevice.log(
            "Test run finished, " +
            "${testRun.passedCount} passed, " +
            "${testRun.failedCount} failed, took " +
            "${testRun.durationNanos.nanosToHumanReadableTime()}."
        )
      }
      .doOnError { adbDevice.log("Error during tests run: $it") }
}

data class PulledFiles(
    val files: List<File>,
    val screenshots: List<File>
)

private fun pullTestFiles(
    adbDevice: AdbDevice,
    test: InstrumentationTest,
    outputDir: File,
    verboseOutput: Boolean
): Single<PulledFiles> = Single.fromCallable {
  val screenshots = File(outputDir, "screenshots")
  val deviceDir = File(screenshots, adbDevice.id)
  File(deviceDir, test.className).apply { mkdirs() }
}
    .flatMap { screenshotsFolderOnHostMachine ->
      val folderOnDevice = "/storage/emulated/0/app_spoon-screenshots/${test.className}/${test.testName}"
      Single.concat(
          adbDevice.pullFolder(
              folderOnDevice = folderOnDevice,
              folderOnHostMachine = screenshotsFolderOnHostMachine,
              logErrors = verboseOutput
          ),
          adbDevice.deleteFolder(
              folderOnDevice = folderOnDevice,
              logErrors = verboseOutput
          )
      )
          .lastOrError()
          .map { File(screenshotsFolderOnHostMachine, test.testName) }
    }
    .map { screenshotsFolderOnHostMachine ->
      PulledFiles(
          files = emptyList(),
          screenshots = screenshotsFolderOnHostMachine
              .takeIf { it.exists() }
              ?.listFiles()
              ?.toList()
              .orEmpty()
      )
    }

@Suppress("ReturnCount")
internal fun String.parseTestClassAndName(): Pair<String, String>? {
  val index = indexOf("TestRunner")
  if (index < 0) return null

  val tokens = substring(index, length).split(':')
  if (tokens.size != 3) return null

  val startedOrFinished = tokens[1].trimStart()
  if (startedOrFinished == "started" || startedOrFinished == "finished") {
    return tokens[2].substringAfter("(").removeSuffix(")") to tokens[2].substringBefore("(").trim()
  }
  return null
}

private fun saveLogcat(
    adbDevice: AdbDevice,
    logsDir: File
): Observable<Pair<String, String>> {
  val fullLogcatFile = logcatFileForDevice(logsDir)
  return adbDevice.redirectLogcatToFile(fullLogcatFile)
      .flatMap { _: Process ->
        data class CaptureState(
            val logcat: String = "",
            val startedTestClassAndName: Pair<String, String>? = null,
            val finishedTestClassAndName: Pair<String, String>? = null
        )

        tail(fullLogcatFile)
            .scan(CaptureState()) { previous, newline ->
              val complete = previous.startedTestClassAndName != null &&
                             previous.finishedTestClassAndName != null
              val logcat = when {
                complete -> newline
                else -> "${previous.logcat}\n$newline"
              }

              // Implicitly expecting to see logs from
              // `android.support.test.internal.runner.listener.LogRunListener`.
              // Was not able to find more reliable solution to capture logcat per test.
              val startedTest: Pair<String, String>? = newline.parseTestClassAndName()
              val finishedTest: Pair<String, String>? = newline.parseTestClassAndName()

              CaptureState(
                  logcat = logcat,
                  startedTestClassAndName = startedTest
                                            ?: previous.startedTestClassAndName,
                  finishedTestClassAndName = finishedTest // Actual finished test should always overwrite previous.
              )
            }
            .filter { it.startedTestClassAndName != null && it.startedTestClassAndName == it.finishedTestClassAndName }
            .map { result ->
              logcatFileForTest(
                  logsDir,
                  className = result.startedTestClassAndName!!.first,
                  testName = result.startedTestClassAndName.second
              )
                  .apply { parentFile.mkdirs() }
                  .writeText(result.logcat)

              result.startedTestClassAndName
            }
      }
}

private fun logcatFileForDevice(logsDir: File) = File(logsDir, "full.logcat")

private fun logcatFileForTest(
    logsDir: File,
    className: String,
    testName: String
): File = File(File(logsDir, className), "$testName.logcat")

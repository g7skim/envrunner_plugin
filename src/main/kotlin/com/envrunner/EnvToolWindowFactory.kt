package com.envrunner

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.DefaultComboBoxModel
import javax.swing.event.PopupMenuListener
import java.awt.event.ItemEvent
import javax.swing.border.EmptyBorder
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Paths

class EnvToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        // Root panel: BorderLayout to anchor controls+logs at the bottom reliably
        val panel = JPanel(java.awt.BorderLayout())

        // A sub-panel for form-like controls; use BoxLayout so hiding rows collapses space
        val formPanel = JPanel()
        formPanel.layout = javax.swing.BoxLayout(formPanel, javax.swing.BoxLayout.Y_AXIS)

        val platforms = arrayOf("PLA", "FE", "TAP", "CORE_OPS", "CORE_OPS_EUROUAT", "CORE_OPS_BIZ", "PROD", "XMNUP")
        val envs = arrayOf("VI11_1", "VI7_1", "SFC_1", "CORE_OPS", "CORE_OPS_EUROUAT", "CORE_OPS_BIZ", "PROD")
        val bools = arrayOf("true", "false")

        val platformDropdown = ComboBox(platforms)
        val e2eEnvDropdown = ComboBox(envs)
        val usePreUrl = ComboBox(bools)
        val mockServer = ComboBox(bools)
        // CI is always true and hidden from UI

        val grepField = JBTextField("RCV-7364")

        // New variables
        val canaryField = JBTextField("")
        val regressionFFsField = JBTextField("{}")

        val projects = arrayOf(
            "chrome",
            "chromium",
            "firefox",
            "edge",
            "mobile-chromium",
            "bs-android-chrome",
            "bs-ios-safari"
        )
        val projectDropdown = ComboBox(projects)

        val timeoutField = JBTextField("280000")
        val repeatField = JBTextField("0")
        val workersField = JBTextField("1")

        val modes = arrayOf("headed", "debug", "headless")
        val modeDropdown = ComboBox(modes)

        // Working directory field: default to packages/automation-testing
        val workDirField = JBTextField("packages/automation-testing")

        fun paddedLabel(text: String, tooltip: String? = null): JBLabel = JBLabel(text).apply {
            border = EmptyBorder(0, 6, 0, 0)
            toolTipText = tooltip
        }

        fun row(label: JBLabel, component: java.awt.Component): JPanel {
            val r = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2))
            r.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            r.add(label)
            r.add(component)
            // Make row stretch horizontally but keep preferred height
            r.maximumSize = java.awt.Dimension(Int.MAX_VALUE, r.preferredSize.height)
            return r
        }

        val platformRow = row(paddedLabel("PLATFORM", "Target platform for tests"), platformDropdown)
        formPanel.add(platformRow)

        val e2eLabel = paddedLabel("E2E_ENV", "Backend E2E environment. Hidden when PLATFORM=PROD (forced to PROD)")
        val e2eRow = row(e2eLabel, e2eEnvDropdown)
        formPanel.add(e2eRow)

        val useUrlLabel = paddedLabel("USE_PREDEFINED_ENV_URL", "Use predefined ENV URL. Hidden when PLATFORM=PROD (forced to false)")
        val useUrlRow = row(useUrlLabel, usePreUrl)
        formPanel.add(useUrlRow)

        val mockRow = row(paddedLabel("MOCK_SERVER", "Enable mock server (true/false)"), mockServer)
        formPanel.add(mockRow)

        // CI is hidden but will be passed as true

        formPanel.add(row(paddedLabel("RCV_CANARY_VERSION", "Canary version which will be added as /?rcv_canary_version query parameter value. Example: 25.9.12-rcv-136333"), canaryField))

        formPanel.add(row(paddedLabel("RWC_REGRESSION_FFS", "JSON with feature flags to be applied to the app via localStorage during regression runs"), regressionFFsField))

        formPanel.add(row(paddedLabel("Test grep", "Filter tests by name. Separate with | if there are multiple tests"), grepField))

        formPanel.add(row(paddedLabel("Browser", "Playwright browser name to run"), projectDropdown))

        // BrowserStack credentials (conditionally visible)
        val bsUsernameField = JBTextField("")
        val bsAccessKeyField = JBPasswordField()
        // Mask access key by default and reveal on hover
        bsAccessKeyField.echoChar = '*'
        bsAccessKeyField.toolTipText = "Hover to reveal"
        bsAccessKeyField.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                bsAccessKeyField.echoChar = 0.toChar()
            }
            override fun mouseExited(e: MouseEvent) {
                bsAccessKeyField.echoChar = '*'
            }
        })
        val bsUserRow = row(paddedLabel("BROWSERSTACK_USERNAME", "Your BrowserStack username"), bsUsernameField)
        val bsKeyRow = row(paddedLabel("BROWSERSTACK_ACCESS_KEY", "Your BrowserStack access key"), bsAccessKeyField)
        formPanel.add(bsUserRow)
        formPanel.add(bsKeyRow)

        formPanel.add(row(paddedLabel("Timeout", "Playwright test timeout in ms (> 0)"), timeoutField))

        formPanel.add(row(paddedLabel("Repeat-each", "Run each test N times (default: 1)"), repeatField))

        formPanel.add(
            row(
                paddedLabel(
                    "Workers",
                    "Number of concurrent workers or percentage of logical CPU cores, use 1 to run in a single worker"
                ),
                workersField
            )
        )

        formPanel.add(row(paddedLabel("Run mode", "headed/debug/headless"), modeDropdown))
        // Working Directory is fixed; keep the value internally but hide the field from UI

        // Put the form into a scroll pane in the CENTER so the bottom area stays fixed
        // when the window gets smaller
        val formScroll = JBScrollPane(formPanel)
        panel.add(formScroll, java.awt.BorderLayout.CENTER)

        // Simple log area (fallback that doesn't depend on console modules)
        val logArea = JBTextArea(10, 80)
        logArea.isEditable = false
        val logScroll = JBScrollPane(logArea)

        val runButton = JButton("Start")
        val stopButton = JButton("Stop")
        // Show only Start initially; Stop appears only while tests are running
        stopButton.isVisible = false
        stopButton.isEnabled = false

        val controlsPanel = JPanel()
        controlsPanel.layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4)
        // Show report button
        val showReportButton = JButton("Show latest report")
        val reportButtonDefaultText = showReportButton.text
        controlsPanel.add(runButton)
        controlsPanel.add(stopButton)
        controlsPanel.add(showReportButton)
        controlsPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT

        // React to PLATFORM selection
        fun refreshEnvControlsVisibility() {
            val isProd = platformDropdown.selectedItem?.toString() == "PROD"
            e2eRow.isVisible = !isProd
            useUrlRow.isVisible = !isProd

            if (isProd) {
                // Force defaults when hidden
                // Restore full model (with PROD) when switching to PROD view
                e2eEnvDropdown.model = DefaultComboBoxModel(envs)
                e2eEnvDropdown.selectedItem = "PROD"
                usePreUrl.selectedItem = "false"
            } else {
                // Ensure PROD option is not available in non‑PROD mode
                val nonProdEnvs = envs.filter { it != "PROD" }.toTypedArray()
                val current = e2eEnvDropdown.selectedItem?.toString()
                e2eEnvDropdown.model = DefaultComboBoxModel(nonProdEnvs)
                if (current != null && current != "PROD" && nonProdEnvs.contains(current)) {
                    e2eEnvDropdown.selectedItem = current
                }
            }
            formPanel.revalidate(); formPanel.repaint()
        }
        fun refreshBsVisibility() {
            val value = projectDropdown.selectedItem?.toString()
            val isBs = value == "bs-android-chrome" || value == "bs-ios-safari"
            bsUserRow.isVisible = isBs
            bsKeyRow.isVisible = isBs
            formPanel.revalidate(); formPanel.repaint()
        }
        platformDropdown.addItemListener { ev ->
            if (ev.stateChange == ItemEvent.SELECTED) refreshEnvControlsVisibility()
        }
        // Initialize
        refreshEnvControlsVisibility()
        projectDropdown.addItemListener { ev ->
            if (ev.stateChange == ItemEvent.SELECTED) refreshBsVisibility()
        }
        refreshBsVisibility()

        var currentHandler: OSProcessHandler? = null

        runButton.addActionListener {
            // Validate numeric fields
            val timeout = timeoutField.text.trim()
            val repeat = repeatField.text.trim()
            val workers = workersField.text.trim()

            val timeoutNum = timeout.toLongOrNull()
            val repeatNum = repeat.toIntOrNull()
            val workersValid = isValidWorkers(workers)
            if (timeoutNum == null || timeoutNum <= 0 || repeatNum == null || repeatNum < 0 || !workersValid) {
                val msg = buildString {
                    append("Please provide valid values.\n")
                    append("- Timeout > 0 (ms)\n")
                    append("- Repeat-each >= 0\n")
                    append("- Workers: positive integer (e.g., 1) or percentage 1%..100% (e.g., 50%)")
                }
                Messages.showErrorDialog(project, msg, "PW Runner")
                return@addActionListener
            }

            // Build command
            val cmd = CommandBuilder.build(
                platformDropdown.selectedItem?.toString() ?: "",
                e2eEnvDropdown.selectedItem?.toString() ?: "",
                usePreUrl.selectedItem?.toString() ?: "",
                mockServer.selectedItem?.toString() ?: "",
                "true", // CI is always true
                canaryField.text.trim(),
                regressionFFsField.text.trim(),
                grepField.text,
                projectDropdown.selectedItem?.toString() ?: "",
                timeout,
                repeat,
                workers,
                modeDropdown.selectedItem?.toString() ?: "",
                "PLAYWRIGHT",
                bsUsernameField.text.trim(),
                String(bsAccessKeyField.password).trim()
            )
            // Persist current selections
            saveState(project, platformDropdown, e2eEnvDropdown, usePreUrl, mockServer, grepField, projectDropdown, timeoutField, repeatField, workersField, modeDropdown, workDirField, canaryField, regressionFFsField, bsUsernameField, bsAccessKeyField)

            // Clear logs and run
            logArea.text = ""
            // Resolve working directory
            val basePath = project.basePath ?: ""
            val wdRaw = workDirField.text.trim()
            val resolvedWd = try {
                val p = Paths.get(wdRaw)
                val abs = if (p.isAbsolute) p else Paths.get(basePath).resolve(wdRaw)
                abs.normalize()
            } catch (t: Throwable) {
                null
            }

            if (resolvedWd == null || !Files.isDirectory(resolvedWd)) {
                val msg = "Working directory not found: ${wdRaw}. Expected at: ${Paths.get(basePath).resolve(wdRaw).normalize()}"
                Messages.showErrorDialog(project, msg, "PW Runner")
                logArea.append("$msg\n")
                return@addActionListener
            }

            val handler = TestExecutor.createProcessHandler(project, cmd, resolvedWd.toString())
            currentHandler = handler
            // Update UI state: replace Start with Stop while running
            runButton.isEnabled = false
            runButton.isVisible = false
            stopButton.isEnabled = true
            stopButton.isVisible = true
            // Disable report button during run
            showReportButton.isEnabled = false
            showReportButton.text = "Please wait until tests finish…"
            showReportButton.toolTipText = "Please wait until tests finish to open the latest report"
            controlsPanel.revalidate()
            controlsPanel.repaint()

            val startTime = System.currentTimeMillis()

            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text
                    SwingUtilities.invokeLater {
                        logArea.append(text)
                        logArea.caretPosition = logArea.document.length
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    val code = event.exitCode
                    val duration = System.currentTimeMillis() - startTime
                    SwingUtilities.invokeLater {
                        runButton.isEnabled = true
                        runButton.isVisible = true
                        stopButton.isEnabled = false
                        stopButton.isVisible = false
                        // Re-enable report button after run
                        showReportButton.isEnabled = true
                        showReportButton.text = reportButtonDefaultText
                        showReportButton.toolTipText = null
                        controlsPanel.revalidate()
                        controlsPanel.repaint()
                    }
                    currentHandler = null
                    SwingUtilities.invokeLater {
                        logArea.append("\nProcess finished with exit code $code\n")
                        logArea.caretPosition = logArea.document.length
                    }
                    notifyFinish(project, code, duration)
                }
            })
            handler.startNotify()
        }

        stopButton.addActionListener {
            stopButton.isEnabled = false
            currentHandler?.let {
                if (!it.isProcessTerminated) {
                    it.destroyProcess()
                    // Allow process to terminate gracefully; avoid calling protected killProcessTree()
                }
            }
        }

        // Open latest Playwright HTML report via yarn command
        showReportButton.addActionListener {
            // Resolve working directory similar to run flow
            val basePath = project.basePath ?: ""
            val wdRaw = workDirField.text.trim().ifEmpty { "packages/automation-testing" }
            val resolvedWd = try {
                val p = Paths.get(wdRaw)
                val abs = if (p.isAbsolute) p else Paths.get(basePath).resolve(wdRaw)
                abs.normalize()
            } catch (t: Throwable) {
                null
            }

            if (resolvedWd == null || !Files.isDirectory(resolvedWd)) {
                val msg = "Working directory not found: ${wdRaw}. Expected at: ${Paths.get(basePath).resolve(wdRaw).normalize()}"
                Messages.showErrorDialog(project, msg, "PW Runner")
                logArea.append("$msg\n")
                return@addActionListener
            }

            // Prevent concurrent clicks
            showReportButton.isEnabled = false
            showReportButton.text = "Opening report…"
            showReportButton.toolTipText = null

            // Orchestrate: if port busy, kill existing report then start a fresh one
            val defaultPort = 9323
            val workDir = resolvedWd.toString()

            fun runReportCommand(portArg: String) {
                val cmd = "yarn playwright show-report playwright-report $portArg".trim()
                logArea.append("Running: $cmd (cwd=${resolvedWd})\n")
                val handler = TestExecutor.createProcessHandler(project, cmd, workDir)
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        val text = event.text
                        SwingUtilities.invokeLater {
                            logArea.append(text)
                            logArea.caretPosition = logArea.document.length
                        }
                        // If still seeing address already in use, retry once on random port
                        if (text.contains("address already in use", ignoreCase = true)) {
                            // Retry on port 0 (random)
                            SwingUtilities.invokeLater {
                                logArea.append("Port is busy, retrying on a random port...\n")
                                logArea.caretPosition = logArea.document.length
                            }
                            // We won't try to kill again; just start on random port
                            runReportCommand("--port=0")
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        val code = event.exitCode
                        SwingUtilities.invokeLater {
                            logArea.append("\nShow report process finished with exit code $code\n")
                            logArea.caretPosition = logArea.document.length
                            // Re-enable button after completion (if tests aren't running, it's enabled)
                            showReportButton.isEnabled = true
                            showReportButton.text = reportButtonDefaultText
                            showReportButton.toolTipText = null
                        }
                        val type = if (code == 0) NotificationType.INFORMATION else NotificationType.WARNING
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("PW Runner")
                            .createNotification("Show report finished (exit $code)", type)
                            .notify(project)
                    }
                })
                handler.startNotify()
            }

            // Attempt to free the default port if occupied
            if (isPortInUse(defaultPort)) {
                logArea.append("Port $defaultPort is in use. Attempting to stop existing report server...\n")
                val killed = killProcessesOnPort(defaultPort, workDir)
                if (killed) {
                    logArea.append("Killed processes on port $defaultPort.\n")
                    // brief wait to let OS release the port
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                } else {
                    logArea.append("No processes killed or kill failed for port $defaultPort. Will retry on random port if needed.\n")
                }
            }

            // Prefer explicit default port first
            runReportCommand("--port=$defaultPort")
        }

        // Bottom area: controls directly above the log window
        val bottomPanel = JPanel()
        bottomPanel.layout = javax.swing.BoxLayout(bottomPanel, javax.swing.BoxLayout.Y_AXIS)
        bottomPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        bottomPanel.add(controlsPanel)

        // Size the log area to show about 10–12 lines by default
        val lineHeight = logArea.getFontMetrics(logArea.font).height
        val preferredLogHeight = lineHeight * 12 // ~10 lines + padding
        val preferredLogWidth = 600
        logScroll.preferredSize = java.awt.Dimension(preferredLogWidth, preferredLogHeight)
        logScroll.maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredLogHeight)
        logScroll.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        bottomPanel.add(logScroll)

        panel.add(bottomPanel, java.awt.BorderLayout.SOUTH)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // Dispose process on content removal
        content.setDisposer {
            currentHandler?.let { h ->
                if (!h.isProcessTerminated) h.destroyProcess()
            }
        }
        toolWindow.contentManager.addContent(content)

        // Load last saved values
        loadState(project, platformDropdown, e2eEnvDropdown, usePreUrl, mockServer, grepField, projectDropdown, timeoutField, repeatField, workersField, modeDropdown, workDirField, canaryField, regressionFFsField, bsUsernameField, bsAccessKeyField)
        // After loading, reapply PROD logic (in case restored selection is PROD)
        refreshEnvControlsVisibility()
        refreshBsVisibility()
    }

    // Helpers
    private fun isPortInUse(port: Int): Boolean {
        // Try connecting (IPv4 and IPv6) — if connection succeeds, the port is in use.
        fun canConnect(host: String): Boolean = try {
            java.net.Socket()
                .apply { connect(java.net.InetSocketAddress(host, port), 150) }
                .use { true }
        } catch (_: Throwable) { false }

        if (canConnect("127.0.0.1") || canConnect("::1")) return true
        // As a fallback, try to bind; if bind fails with BindException, it's in use.
        return try {
            java.net.ServerSocket(port).use { false }
        } catch (_: java.net.BindException) {
            true
        } catch (_: Throwable) {
            // On unexpected errors, assume it's in use to be safe
            true
        }
    }

    private fun killProcessesOnPort(port: Int, workDir: String): Boolean {
        // macOS/Linux approach using lsof (avoid GNU-only xargs -r)
        val shell = """
            PIDS=$(lsof -ti tcp:$port 2>/dev/null)
            if [ -n "${'$'}PIDS" ]; then echo "${'$'}PIDS" | xargs kill -9 2>/dev/null || true; fi
        """.trimIndent()
        val command = arrayOf("/bin/sh", "-c", shell)
        return try {
            val pb = ProcessBuilder(*command)
            pb.directory(java.io.File(workDir))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            // If after this the port is free, we consider it success
            !isPortInUse(port)
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyFinish(project: Project, exitCode: Int, durationMs: Long) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("PW Runner")
        val seconds = durationMs / 1000.0
        val type = if (exitCode == 0) NotificationType.INFORMATION else NotificationType.ERROR
        val msg = if (exitCode == 0) {
            "Tests finished successfully in ${"%.1f".format(seconds)}s (exit code 0)"
        } else {
            "Tests failed with exit code $exitCode in ${"%.1f".format(seconds)}s"
        }
        group.createNotification(msg, type).notify(project)
    }

    private fun saveState(
        project: Project,
        platformDropdown: ComboBox<String>,
        e2eEnvDropdown: ComboBox<String>,
        usePreUrl: ComboBox<String>,
        mockServer: ComboBox<String>,
        grepField: JBTextField,
        projectDropdown: ComboBox<String>,
        timeoutField: JBTextField,
        repeatField: JBTextField,
        workersField: JBTextField,
        modeDropdown: ComboBox<String>,
        workDirField: JBTextField,
        canaryField: JBTextField,
        regressionFFsField: JBTextField,
        bsUsernameField: JBTextField,
        bsAccessKeyField: JBPasswordField
    ) {
        val props = PropertiesComponent.getInstance(project)
        props.setValue(KEY_PLATFORM, platformDropdown.selectedItem?.toString(), "")
        props.setValue(KEY_E2E_ENV, e2eEnvDropdown.selectedItem?.toString(), "")
        props.setValue(KEY_USE_URL, usePreUrl.selectedItem?.toString(), "")
        props.setValue(KEY_MOCK, mockServer.selectedItem?.toString(), "")
        props.setValue(KEY_GREP, grepField.text, "")
        props.setValue(KEY_PROJECT, projectDropdown.selectedItem?.toString(), "")
        props.setValue(KEY_TIMEOUT, timeoutField.text, "")
        props.setValue(KEY_REPEAT, repeatField.text, "")
        props.setValue(KEY_WORKERS, workersField.text, "1")
        props.setValue(KEY_MODE, modeDropdown.selectedItem?.toString(), "")
        props.setValue(KEY_WORKDIR, workDirField.text, "packages/automation-testing")
        props.setValue(KEY_CANARY, canaryField.text, "")
        props.setValue(KEY_FFS, regressionFFsField.text, "")
        props.setValue(KEY_BS_USER, bsUsernameField.text, "")
        props.setValue(KEY_BS_KEY, String(bsAccessKeyField.password), "")
    }

    private fun loadState(
        project: Project,
        platformDropdown: ComboBox<String>,
        e2eEnvDropdown: ComboBox<String>,
        usePreUrl: ComboBox<String>,
        mockServer: ComboBox<String>,
        grepField: JBTextField,
        projectDropdown: ComboBox<String>,
        timeoutField: JBTextField,
        repeatField: JBTextField,
        workersField: JBTextField,
        modeDropdown: ComboBox<String>,
        workDirField: JBTextField,
        canaryField: JBTextField,
        regressionFFsField: JBTextField,
        bsUsernameField: JBTextField,
        bsAccessKeyField: JBPasswordField
    ) {
        val props = PropertiesComponent.getInstance(project)
        fun setIfPresent(combo: ComboBox<String>, value: String?) {
            if (!value.isNullOrEmpty()) combo.selectedItem = value
        }
        grepField.text = props.getValue(KEY_GREP, grepField.text)
        timeoutField.text = props.getValue(KEY_TIMEOUT, timeoutField.text)
        repeatField.text = props.getValue(KEY_REPEAT, repeatField.text)
        workersField.text = props.getValue(KEY_WORKERS, workersField.text)
        setIfPresent(platformDropdown, props.getValue(KEY_PLATFORM, ""))
        setIfPresent(e2eEnvDropdown, props.getValue(KEY_E2E_ENV, ""))
        setIfPresent(usePreUrl, props.getValue(KEY_USE_URL, ""))
        setIfPresent(mockServer, props.getValue(KEY_MOCK, ""))
        setIfPresent(projectDropdown, props.getValue(KEY_PROJECT, ""))
        setIfPresent(modeDropdown, props.getValue(KEY_MODE, ""))
        workDirField.text = props.getValue(KEY_WORKDIR, workDirField.text)
        canaryField.text = props.getValue(KEY_CANARY, canaryField.text)
        regressionFFsField.text = props.getValue(KEY_FFS, regressionFFsField.text)
        bsUsernameField.text = props.getValue(KEY_BS_USER, bsUsernameField.text)
        bsAccessKeyField.text = props.getValue(KEY_BS_KEY, String(bsAccessKeyField.password))
    }

    companion object Keys {
        private const val KEY_PLATFORM = "envrunner.platform"
        private const val KEY_E2E_ENV = "envrunner.e2eEnv"
        private const val KEY_USE_URL = "envrunner.usePredefinedUrl"
        private const val KEY_MOCK = "envrunner.mockServer"
        private const val KEY_GREP = "envrunner.grep"
        private const val KEY_PROJECT = "envrunner.project"
        private const val KEY_TIMEOUT = "envrunner.timeout"
        private const val KEY_REPEAT = "envrunner.repeat"
        private const val KEY_WORKERS = "envrunner.workers"
        private const val KEY_MODE = "envrunner.mode"
        private const val KEY_WORKDIR = "envrunner.workdir"
        private const val KEY_CANARY = "envrunner.canary"
        private const val KEY_FFS = "envrunner.regressionFFs"
        private const val KEY_BS_USER = "envrunner.bsUsername"
        private const val KEY_BS_KEY = "envrunner.bsAccessKey"
    }

    private fun isValidWorkers(value: String): Boolean {
        if (value.isBlank()) return false
        val v = value.trim()
        // Allow integer >= 1
        val intVal = v.toIntOrNull()
        if (intVal != null) return intVal >= 1
        // Or percentage 1%..100%
        if (v.endsWith("%")) {
            val num = v.dropLast(1).toIntOrNull()
            if (num != null) return num in 1..100
        }
        return false
    }
}

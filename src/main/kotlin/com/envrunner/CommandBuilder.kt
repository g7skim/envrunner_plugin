package com.envrunner

object CommandBuilder {

    private fun shQuote(value: String): String {
        // POSIX-safe single-quote: close ', insert '"'"', reopen '
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun build(
        platform: String,
        e2eEnv: String,
        useUrl: String,
        mock: String,
        ciValue: String,
        canaryVersion: String,
        regressionFFs: String,
        grep: String,
        project: String,
        timeout: String,
        repeat: String,
        workers: String,
        mode: String,
        runner: String,
        bsUsername: String = "",
        bsAccessKey: String = ""
    ) : String {

        // Apply conditional rules:
        // If PLATFORM=PROD, force E2E_ENV=PROD and USE_PREDEFINED_ENV_URL=false
        val effectiveE2eEnv = if (platform == "PROD") "PROD" else e2eEnv
        val effectiveUseUrl = if (platform == "PROD") "false" else useUrl

        // Quote all env values to avoid shell splitting (e.g., JSON in RWC_REGRESSION_FFS)
        val env = listOf(
            "PLATFORM=${shQuote(platform)}",
            "E2E_ENV=${shQuote(effectiveE2eEnv)}",
            "USE_PREDEFINED_ENV_URL=${shQuote(effectiveUseUrl)}",
            "MOCK_SERVER=${shQuote(mock)}",
            "CI=${shQuote(ciValue)}",
            "RCV_CANARY_VERSION=${shQuote(canaryVersion)}",
            "RWC_REGRESSION_FFS=${shQuote(regressionFFs)}"
        ).joinToString(" ")

        val isPlaywright = when (runner.uppercase()) {
            "PLAYWRIGHT" -> true
            "JEST" -> false
            else -> true // default to Playwright for backward compatibility
        }

        val base = if (isPlaywright) {
            // Run via yarn test as requested; project scripts are expected to forward to Playwright
            val modeFlag = when (mode) {
                "headed" -> "--headed"
                "debug" -> "--debug"
                else -> ""
            }
            val grepArg = if (grep.isNotBlank()) "\"$grep\"" else ""
            val parts = listOf(
                "yarn test",
                grepArg,
                "--project='$project'",
                "--timeout=$timeout",
                "--reporter=html",
                "--trace=on",
                "--retries=0",
                "--repeat-each=$repeat",
                "--workers=$workers",
                modeFlag
            ).filter { it.isNotBlank() }
            parts.joinToString(" ")
        } else {
            // Jest CLI
            // Map grep to -t/--testNamePattern, map timeout to --testTimeout, drop unsupported flags
            val grepArg = if (grep.isNotBlank()) "-t \"$grep\"" else ""
            val timeoutArg = "--testTimeout=$timeout"
            val inBand = "--runInBand"
            listOf("yarn test", grepArg, timeoutArg, inBand).filter { it.isNotBlank() }.joinToString(" ")
        }

        val isBsAndroid = project == "bs-android-chrome"
        val isBsIos = project == "bs-ios-safari"

        if (isBsAndroid || isBsIos) {
            val device = if (isBsIos) "ios" else "android"
            val bsEnv = listOf(
                "RUN_ID=$(uuidgen)",
                "LOCAL_RUN=true",
                "BS_DEVICE=$device",
                "PROJECT=${shQuote(project)}"
            ).joinToString(" ")

            val creds = listOf(
                if (bsUsername.isNotBlank()) "BROWSERSTACK_USERNAME=${shQuote(bsUsername)}" else "",
                if (bsAccessKey.isNotBlank()) "BROWSERSTACK_ACCESS_KEY=${shQuote(bsAccessKey)}" else ""
            ).filter { it.isNotBlank() }.joinToString(" ")

            val prefix = "yarn kill-browser-stack-port &&"
            return listOf(prefix, bsEnv, creds, env, base)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
        }

        return "$env $base".trim()
    }
}

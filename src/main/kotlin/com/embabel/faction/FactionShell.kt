/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.faction

import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.domain.io.UserInput
import com.embabel.faction.agent.AnalysisRequest
import com.embabel.faction.agent.SplitPredictionResult
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

@ShellComponent
class FactionShell(
    private val agentPlatform: AgentPlatform,
) {

    @ShellMethod("Analyse contributor factions for a GitHub repo and predict splits", key = ["analyse", "analyze"])
    fun analyse(
        @ShellOption(help = "GitHub owner/repo, e.g. nodejs/node") repo: String,
        @ShellOption(defaultValue = "180", help = "Days of history to analyse (ignored if --since is set)") days: Int,
        @ShellOption(defaultValue = "", help = "Start date ISO-8601 (e.g. 2013-06-01); overrides --days") since: String,
        @ShellOption(defaultValue = "", help = "End date ISO-8601 (e.g. 2015-06-01); defaults to now") until: String,
        @ShellOption(defaultValue = "", help = "Comma-separated bot/automation logins to exclude, e.g. elasticmachine,dependabot") bots: String,
    ): String {
        val input = buildString {
            if (since.isNotBlank()) {
                append("$repo $since")
                if (until.isNotBlank()) append(" $until")
            } else {
                append("$repo $days")
            }
            if (bots.isNotBlank()) append(" --bots=$bots")
        }
        val result = AgentInvocation
            .create(agentPlatform, SplitPredictionResult::class.java)
            .invoke(UserInput(input))
        return result.content
    }
}

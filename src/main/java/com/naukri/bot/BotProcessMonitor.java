package com.naukri.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Slf4j
public class BotProcessMonitor {

    /**
     * Prints ONLY Playwright (bot) Chrome processes
     */
    public static void printBotProcesses() {
        try {
            Process process = Runtime.getRuntime().exec(
                    "wmic process where \"name='chrome.exe'\" get ProcessId,CommandLine"
            );

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                if (line.contains("--my-bot-id=naukri-bot")) {
                    count++;
                }
            }

        } catch (Exception e) {
            log.error("Error reading processes", e);
        }
    }

    /**
     * Kills ONLY Playwright Chrome processes (safe)
     */
    public static void killBotProcesses() {
        try {
//            Runtime.getRuntime().exec(
//                    "wmic process where \"CommandLine like '%remote-debugging-pipe%'\" call terminate"
//            );

            Runtime.getRuntime().exec(
                    "wmic process where \"CommandLine like '%--my-bot-id=naukri-bot%'\" call terminate"
            );
            log.warn("💀 Killed all Playwright Chrome processes");

        } catch (Exception e) {
            log.error("Error killing processes", e);
        }
    }
}
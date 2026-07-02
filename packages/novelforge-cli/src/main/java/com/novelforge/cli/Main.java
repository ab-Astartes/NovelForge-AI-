package com.novelforge.cli;

import com.novelforge.cli.commands.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NovelForge CLI entry point.
 * Commands: book create, write next, write draft, audit, export, interact, style clone
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        String command = args[0];
        String[] subArgs = args.length > 1 ? subArray(args, 1) : new String[0];

        switch (command) {
            case "book"     -> handleBook(subArgs);
            case "write"    -> handleWrite(subArgs);
            case "audit"    -> handleAudit(subArgs);
            case "export"   -> handleExport(subArgs);
            case "interact" -> handleInteract(subArgs);
            case "style"    -> handleStyle(subArgs);
            case "help"     -> printHelp();
            default         -> { System.err.println("Unknown command: " + command); printHelp(); }
        }
    }

    private static void handleBook(String[] args) {
        BookCommand cmd = new BookCommand();
        cmd.execute(args);
    }

    private static void handleWrite(String[] args) {
        WriteCommand cmd = new WriteCommand();
        cmd.execute(args);
    }

    private static void handleAudit(String[] args) {
        AuditCommand cmd = new AuditCommand();
        cmd.execute(args);
    }

    private static void handleExport(String[] args) {
        ExportCommand cmd = new ExportCommand();
        cmd.execute(args);
    }

    private static void handleInteract(String[] args) {
        InteractCommand cmd = new InteractCommand();
        cmd.execute(args);
    }

    private static void handleStyle(String[] args) {
        StyleCommand cmd = new StyleCommand();
        cmd.execute(args);
    }

    private static void printHelp() {
        System.out.println("""
            NovelForge — AI Novel Writing Engine
            
            Usage: novelforge <command> [options]
            
            Commands:
              book create   Create a new book project
              book list     List book projects
              book info     Show book details
              write next    Write next chapter (full 9-agent pipeline)
              write draft   Write draft only (Architect → Writer, skip quality)
              write audit   Audit existing chapter (Auditor → Reviser)
              audit         Run standalone 33-dimension audit
              export        Export book (EPUB/TXT/MD)
              interact      Start interactive dialogue mode
              style clone   Clone writing style from reference text
              help          Show this help message
            
            Options:
              --book <path>     Book project directory
              --genre <key>     Genre profile (xuanhuan, urban, fantasy, etc.)
              --chapter <num>   Chapter number (for audit)
              --api-key <key>   LLM API key (or set OPENAI_API_KEY)
              --base-url <url>  LLM API base URL (default: https://api.openai.com/v1)
              --model <id>      Override default model
              --silent          Suppress progress output
            """);
    }

    private static String[] subArray(String[] arr, int start) {
        String[] result = new String[arr.length - start];
        System.arraycopy(arr, start, result, 0, result.length);
        return result;
    }
}

package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import picocli.CommandLine.Command;

@Command (name = "config", description = "Show or change a setting")
public class ConfigCommand implements com.eonmux.cadetcoder.CommandRegistry.Command {
    @Override
    public int execute(String[] args) {
        com.eonmux.cadetcoder.config.Configuration config =
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig();
        if (args.length == 0) {
            return listSettings(config);
        } else if (args.length == 1) {
            return showSetting(config, args[0]);
        } else if (args.length == 2) {
            String option = args[0];
            String value  = args[1];
            Integer refused = ModelDispatch.refuseSetupChange("config " + option);
            if (refused != null) {
                return refused;
            }
            try {
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().overrideFromCommandLine(
                        java.util.Collections.singletonMap(option, value)
                                                                                                );
                // Read back what is now in the configuration rather than echoing the argument. A
                // setting that normalises what it is given -- a path beginning with "~", or a blank
                // that means "back to the default" -- was otherwise confirmed with one value and
                // stored as another, and the difference showed up only on the next read.
                String stored       = storedValue(option);
                String displayValue = stored != null ? stored : maskIfSecret(option, value);
                boolean saved = com.eonmux.cadetcoder.config.ConfigManager.getInstance().saveChoice(option);
                if (!saved) {
                    OutputFormatter.printError(
                            "Configuration change applied in memory but could not be saved to disk: " +
                            option + " = " + displayValue);
                    return 1;
                }
                OutputFormatter.printSuccess("Configuration updated: " + option + " = " + displayValue);
            } catch (IllegalArgumentException e) {
                // Printed as it arrives. Every refusal from ConfigOverrides is already a whole
                // sentence naming what is wrong -- a misspelt property, a value outside its range,
                // a setting that no longer exists -- and prefixing them with "Invalid value for
                // '<key>':" repeated the key and, for the last two, gave the wrong reason: the
                // value is beside the point when the property does not exist.
                // Includes NumberFormatException (a subclass) from numeric config parsing.
                OutputFormatter.printError(e.getMessage());
                return 1;
            }
            return 0;
        } else {
            OutputFormatter.printError("Too many arguments.");
            OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
            return 1;
        }
    }

    /**
     * Prints every setting as {@code section.property = value}, grouped by section.
     *
     * <h2>Why not the JSON</h2>
     *
     * <p>This used to print the serialised {@code Configuration} -- eighty lines of pretty-printed
     * JSON, through the info formatter, so the whole document wore a bullet and a hanging indent.
     * Worse, the shape it showed was not the shape the setter takes: changing something requires
     * {@code config ai.model <value>}, and nothing in a nested document tells you to join the two
     * names with a dot. Printing the flattened keys means what you read is what you type back.</p>
     *
     * @param config the live configuration
     * @return {@code 0}, or {@code 1} if it could not be rendered
     */
    private int listSettings(com.eonmux.cadetcoder.config.Configuration config) {
        com.fasterxml.jackson.databind.JsonNode root = redactedTree(config);
        if (root == null) {
            return 1;
        }
        OutputFormatter.printHeader("Settings");
        java.util.List<String> names = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        java.util.List<String> plain = new java.util.ArrayList<>();
        for (String section : names) {
            com.fasterxml.jackson.databind.JsonNode node = root.get(section);
            if (node == null || !node.isObject()) {
                plain.add(section);
                continue;
            }
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            flatten(section, node, rows);
            if (rows.isEmpty()) {
                continue;
            }
            OutputFormatter.println();
            OutputFormatter.printSubheader(section);
            printRows(rows);
        }
        if (!plain.isEmpty()) {
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            for (String name : plain) {
                rows.add(new String[] {name, render(root.get(name))});
            }
            OutputFormatter.println();
            OutputFormatter.printSubheader("paths");
            printRows(rows);
        }
        OutputFormatter.println();
        String prefix = CommandUsage.prefix();
        OutputFormatter.printInfo("Change one with '" + prefix + "config <name> <value>', "
                + "e.g. '" + prefix + "config ai.model gpt-4o'.");
        return 0;
    }

    /**
     * The value one setting now holds, with secrets already masked.
     *
     * @param name the setting as it is typed, {@code <section>.<property>} or a bare name
     * @return the value as it would be printed, or {@code null} when nothing is called that
     */
    private String storedValue(String name) {
        com.fasterxml.jackson.databind.JsonNode root = redactedTree(
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig());
        if (root == null) {
            return null;
        }
        for (String[] row : settings(root)) {
            if (row[0].equalsIgnoreCase(name)) {
                return row[1];
            }
        }
        return null;
    }

    /** Every setting in the tree as {@code name, value}, sections flattened into dotted names. */
    private static java.util.List<String[]> settings(
            com.fasterxml.jackson.databind.JsonNode root) {
        java.util.List<String[]> all      = new java.util.ArrayList<>();
        java.util.List<String>   sections = new java.util.ArrayList<>();
        root.fieldNames().forEachRemaining(sections::add);
        for (String section : sections) {
            com.fasterxml.jackson.databind.JsonNode node = root.get(section);
            if (node != null && node.isObject()) {
                flatten(section, node, all);
            } else {
                all.add(new String[] {section, render(node)});
            }
        }
        return all;
    }

    /** Prints one setting, so checking a value does not mean reading the whole file. */
    private int showSetting(com.eonmux.cadetcoder.config.Configuration config, String name) {
        com.fasterxml.jackson.databind.JsonNode root = redactedTree(config);
        if (root == null) {
            return 1;
        }
        java.util.List<String[]> all = settings(root);
        for (String[] row : all) {
            if (row[0].equalsIgnoreCase(name)) {
                OutputFormatter.println(row[0] + " = " + row[1]);
                return 0;
            }
        }
        OutputFormatter.printError("No such setting: " + name);
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        for (String[] row : all) {
            known.add(row[0]);
        }
        java.util.List<String> near = InputRouter.suggest(name, known, 3);
        if (!near.isEmpty()) {
            OutputFormatter.printInfo("Did you mean: " + String.join(", ", near) + "?");
        }
        OutputFormatter.printInfo("Run '" + CommandUsage.prefix()
                + "config' with no arguments to see every setting.");
        return 1;
    }

    /** The configuration as a tree with every secret already masked, or {@code null} on failure. */
    private com.fasterxml.jackson.databind.JsonNode redactedTree(
            com.eonmux.cadetcoder.config.Configuration config) {
        try {
            // Serialize to a tree and redact secrets before display so API keys are never leaked
            // to the console/scrollback/CI logs. The on-disk config is untouched (we redact a
            // copy, not the live Configuration object).
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(config);
            redactSecrets(root);
            return root;
        } catch (Exception e) {
            OutputFormatter.printError("Failed to read configuration: " + e.getMessage());
            return null;
        }
    }

    /** Collects {@code prefix.leaf -> value} pairs, descending into nested objects. */
    private static void flatten(String prefix, com.fasterxml.jackson.databind.JsonNode node,
                                java.util.List<String[]> into) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            com.fasterxml.jackson.databind.JsonNode child = node.get(name);
            String                                  key   = prefix + "." + name;
            if (child != null && child.isObject() && !child.isEmpty()) {
                flatten(key, child, into);
            } else {
                into.add(new String[] {key, render(child)});
            }
        }
    }

    /** A value as one line: arrays comma-separated, an empty container named rather than shown. */
    private static String render(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull()) {
            return "(unset)";
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                return "(none)";
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : value) {
                parts.add(item.asText());
            }
            return String.join(", ", parts);
        }
        if (value.isObject()) {
            return "(none)";
        }
        String text = value.asText();
        return text.isEmpty() ? "(unset)" : text;
    }

    private static void printRows(java.util.List<String[]> rows) {
        int width = 0;
        for (String[] row : rows) {
            width = Math.max(width, row[0].length());
        }
        for (String[] row : rows) {
            OutputFormatter.println("  " + row[0]
                    + " ".repeat(width - row[0].length()) + "  " + row[1]);
        }
    }

    /**
     * Returns a display-safe rendering of a config value: when the option name looks like a
     * secret (contains key/token/secret/password, case-insensitive) the value is masked so it
     * is never leaked to the console/scrollback/CI logs. Non-secret values are returned as-is.
     */
    private static String maskIfSecret(String option, String value) {
        if (option == null || value == null || value.isEmpty()) {
            return value;
        }
        return isSecretName(option) ? ConnectorSupport.maskKey(value) : value;
    }

    /**
     * Whether a config option/field name denotes a secret value that must be masked. Matches on the
     * leaf segment (after the last '.') so {@code ai.apiKey} is treated as a secret while
     * {@code ai.maxTokens} — a numeric limit that merely contains the substring "token" — is not.
     */
    private static boolean isSecretName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        String leaf  = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : lower;
        return leaf.contains("apikey") || leaf.contains("api_key")
                || leaf.endsWith("key")
                || leaf.contains("secret")
                || leaf.contains("password") || leaf.contains("passwd")
                || leaf.contains("credential")
                || leaf.equals("token") || leaf.endsWith("token") || leaf.endsWith("_token");
    }

    /** Masks any textual field whose name looks like a secret, anywhere in the tree. */
    private static void redactSecrets(com.fasterxml.jackson.databind.JsonNode node) {
        redactSecrets(node, false);
    }

    /**
     * Recursively masks secret values. A textual leaf is redacted when either its own
     * field name looks like a secret OR it sits beneath a secret-named container
     * ({@code maskAllTextual}). Propagating the secret context is essential for maps such
     * as {@code ai.providerApiKeys}, whose entries are keyed by a NON-secret provider id
     * (e.g. "anthropic") but whose VALUES are real API keys: without it those values would
     * be printed verbatim because the entry's field name does not itself look secret.
     */
    private static void redactSecrets(com.fasterxml.jackson.databind.JsonNode node, boolean maskAllTextual) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj =
                    (com.fasterxml.jackson.databind.node.ObjectNode) node;
            java.util.List<String> names = new java.util.ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                com.fasterxml.jackson.databind.JsonNode child       = obj.get(name);
                boolean                                 secretField = isSecretName(name);
                if (child != null && child.isTextual() && !child.asText().isEmpty()
                        && (maskAllTextual || secretField)) {
                    obj.put(name, "***redacted***");
                } else {
                    // Once inside a secret-named container every descendant textual value is
                    // a secret regardless of its own (non-secret) field name.
                    redactSecrets(child, maskAllTextual || secretField);
                }
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                redactSecrets(child, maskAllTextual);
            }
        }
    }


    @Override
    public String getUsage() {
        return "config                      Show every setting\n"
             + "config <name>               Show one setting\n"
             + "config <name> <value>       Change one setting, and save it\n"
             + "  Names are <section>.<property>, exactly as they are listed.";
    }
}

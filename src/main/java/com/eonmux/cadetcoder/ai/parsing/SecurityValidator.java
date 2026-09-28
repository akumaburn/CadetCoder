package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.security.AllowedActions;
import com.eonmux.cadetcoder.security.DangerousCommands;
import com.eonmux.cadetcoder.security.NetworkCommands;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Enhanced security validator for parsed actions.
 * Implements comprehensive security checks to prevent malicious or unsafe operations.
 */
public class SecurityValidator {

    /** How a violation begins when the gate that runs shell lines would refuse the line. */
    public static final String REFUSED_COMMAND = "Shell command refused: ";

    private final DebugLogger debugLogger;
    private final Configuration.SecurityConfig securityConfig;

    /**
     * The gate {@code bash} consults before it starts a process, asked here for its verdict.
     *
     * <p>{@code null} only when no configuration could be read; the screen then falls back to
     * refusing every listed name on the line, which refuses more than the gate would.</p>
     */
    private final com.eonmux.cadetcoder.security.SecurityValidator gate;

    /**
     * How much a reply may hold beyond the largest payload one of its actions may carry.
     *
     * <p>Room for the block markers, the reasoning, and the other actions in the same reply.</p>
     */
    private static final long ENVELOPE_ALLOWANCE = 64L * 1024L;

    // Suspicious file patterns
    private static final Pattern SYSTEM_PATH_PATTERN = Pattern.compile(
        "^(/etc/|/bin/|/sbin/|/usr/bin/|/usr/sbin/|/boot/|/sys/|/proc/|C:\\\\Windows\\\\|C:\\\\Program Files)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Prompt injection patterns.
    //
    // The system-prompt clause is deliberately SCOPED to an exfiltration/override request
    // ("reveal your system prompt") instead of the previous bare "system.*prompt". That bare form
    // matched any sentence mentioning both words on one line, so a legitimate question about this
    // codebase's own prompt builder ("the system prompt is assembled by PromptManager") aborted the
    // entire parse as a security threat.
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
        "(ignore.*previous|disregard.*instruction|new.*instruction|forget.*constraint|override.*safety|"
        + "(?:reveal|show|print|repeat|disclose|output|dump|leak|expose|give|ignore|override|bypass)\\s+"
        + "(?:me\\s+)?(?:your|the|its)\\s+(?:full\\s+|entire\\s+|original\\s+|initial\\s+|complete\\s+)*"
        + "system\\s*(?:prompt|instructions))",
        Pattern.CASE_INSENSITIVE
    );
    
    // File inclusion patterns that could be dangerous
    private static final Pattern DANGEROUS_INCLUSION_PATTERN = Pattern.compile(
        "(\\.\\.[\\/\\\\]|%\\.\\.|~[\\/\\\\]|\\$\\{|\\$\\(|`|\\|)",
        Pattern.CASE_INSENSITIVE
    );
    
    public SecurityValidator() {
        this.debugLogger = DebugLogger.getInstance();
        Configuration.SecurityConfig tempConfig = null;
        com.eonmux.cadetcoder.security.SecurityValidator tempGate = null;
        try {
            Configuration config = ConfigManager.getInstance().getConfig();
            tempConfig = config != null ? config.getSecurity() : null;
            tempGate   = tempConfig != null ? new com.eonmux.cadetcoder.security.SecurityValidator()
                                            : null;
        } catch (Exception e) {
            debugLogger.warn("SecurityValidator", "Failed to load security configuration, using defaults: " + e.getMessage());
        }
        this.securityConfig = tempConfig;
        this.gate           = tempGate;
    }
    
    /**
     * Validates a parsed action for security concerns.
     * 
     * @param action The action to validate
     * @param context The parsing context
     * @return ValidationResult with security assessment
     */
    public ValidationResult validateAction(ParsedAction action, ParsingContext context) {
        debugLogger.debug("SecurityValidator", "Validating action: " + action.getCommand());
        
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SecurityLevel riskLevel = SecurityLevel.LOW;
        
        // Basic null checks
        if (action == null || action.getCommand() == null) {
            violations.add("Null or invalid action");
            return new ValidationResult(SecurityLevel.HIGH, violations, warnings);
        }
        
        String command = action.getCommand().toLowerCase().trim();
        
        // Asked of AllowedActions rather than matched here, so this path and the agent harness
        // cannot disagree about what the setting permits.
        String notAllowed = AllowedActions.reasonToRefuse(command);
        if (notAllowed != null) {
            violations.add(notAllowed);
            riskLevel = SecurityLevel.HIGH;
        }
        
        // Check for dangerous commands
        if (DangerousCommands.isDangerous(command)) {
            violations.add("Dangerous command detected: " + command);
            riskLevel = SecurityLevel.HIGH;
        }
        
        // Check for bash commands with dangerous content.
        //
        // The action says what line it will run; this does not decide for itself. Deciding for
        // itself is what let the screen be skipped: it asked whether the verb was literally "bash",
        // "shell" or "execute", while CommandAliases -- the class that exists BECAUSE a synonym
        // silently skipped this very check -- also maps "run" and "exec" onto the shell runner. And
        // it read only the "command" parameter, which an ACTION_START block does not produce: that
        // front end writes the line into "args", so the agentic loop's own shape was screened as if
        // it carried no command at all.
        String bashCommand = action.shellCommandLine();
        if (bashCommand != null) {
            SecurityLevel bashRisk = validateBashCommand(bashCommand, violations, warnings);
            if (bashRisk.ordinal() > riskLevel.ordinal()) {
                riskLevel = bashRisk;
            }
        }
        
        // Check file path security
        String filePath = action.getFilePath();
        if (filePath != null) {
            SecurityLevel pathRisk = validateFilePath(filePath, violations, warnings);
            if (pathRisk.ordinal() > riskLevel.ordinal()) {
                riskLevel = pathRisk;
            }
        }
        
        // Check for prompt injection attempts
        String reasoning = action.getReasoning();
        if (reasoning != null && INJECTION_PATTERN.matcher(reasoning).find()) {
            violations.add("Potential prompt injection detected in reasoning");
            riskLevel = SecurityLevel.HIGH;
        }
        
        // Check parameters for suspicious content
        for (Map.Entry<String, Object> param : action.getParameters().entrySet()) {
            if (param.getValue() != null) {
                String value = param.getValue().toString();
                if (DANGEROUS_INCLUSION_PATTERN.matcher(value).find()) {
                    warnings.add("Suspicious pattern in parameter '" + param.getKey() + "': " + value);
                    if (riskLevel == SecurityLevel.LOW) {
                        riskLevel = SecurityLevel.MEDIUM;
                    }
                }
            }
        }
        
        // Check for attempts to access outside project directory
        if (securityConfig != null && !securityConfig.isAllowOutsideProject() && filePath != null) {
            String workingDir = context.getWorkingDirectory();
            if (workingDir != null && !isPathWithinProject(filePath, workingDir)) {
                violations.add("Attempt to access file outside project directory: " + filePath);
                riskLevel = SecurityLevel.HIGH;
            }
        }
        
        // Check file size limits for write operations
        if ((command.equals("write") || command.equals("multiedit")) && action.hasParameter("content")) {
            Object rawContent = action.getParameters().get("content");
            String content = rawContent == null ? "" : rawContent.toString();
            long maxSizeBytes = maxContentBytes();
            if (content.length() > maxSizeBytes) {
                violations.add("Content size exceeds maximum allowed size: "
                                       + (maxSizeBytes / 1024 / 1024) + "MB");
                riskLevel = SecurityLevel.HIGH;
            }
        }
        
        // Log security events
        if (!violations.isEmpty() || !warnings.isEmpty()) {
            debugLogger.warn("SecurityValidator", 
                String.format("Security assessment for %s: Level=%s, Violations=%d, Warnings=%d", 
                             command, riskLevel, violations.size(), warnings.size()));
        }
        
        return new ValidationResult(riskLevel, violations, warnings);
    }
    
    private SecurityLevel validateBashCommand(String bashCommand, List<String> violations, List<String> warnings) {
        SecurityLevel riskLevel = SecurityLevel.LOW;

        if (bashCommand == null) return riskLevel;

        // Check for network access
        boolean networkRefused = false;
        if (NetworkCommands.isReferencedBy(bashCommand)) {
            if (securityConfig != null && securityConfig.isAllowRemoteExecution()) {
                warnings.add("Network command detected: " + bashCommand);
                riskLevel = SecurityLevel.MEDIUM;
            } else {
                violations.add("Network access not allowed: " + bashCommand);
                riskLevel = SecurityLevel.HIGH;
                networkRefused = true;
            }
        }

        // The gate screens network access first, so a line refused above would only be refused
        // again for the same reason.
        if (!networkRefused && screenPrograms(bashCommand, violations, warnings)) {
            riskLevel = SecurityLevel.HIGH;
        }
        
        // Check for command injection patterns
        if (bashCommand.contains(";") || bashCommand.contains("&&") || 
            bashCommand.contains("||") || bashCommand.contains("`") ||
            bashCommand.contains("$(") || bashCommand.contains("${")) {
            warnings.add("Complex bash command with potential injection vectors");
            if (riskLevel == SecurityLevel.LOW) {
                riskLevel = SecurityLevel.MEDIUM;
            }
        }
        
        return riskLevel;
    }
    
    /**
     * Asks the gate that runs shell lines what it would make of this one.
     *
     * <h2>Why this screen does not judge the line itself</h2>
     *
     * <p>It used to refuse every listed name anywhere on the line, so {@code git rm --cached x}
     * was refused for the word {@code rm}, which the gate reads as an argument of {@code git}. The
     * model was told its action was not allowed, although the same line typed by the user runs.
     * The gate reads which programs a line starts, through operators, substitutions, {@code sh -c}
     * and wrappers such as {@code env} and {@code sudo}; one verdict for both steps keeps them from
     * disagreeing.</p>
     *
     * <p>A line the gate only cannot read -- a script file, a program built by expansion -- is a
     * warning here. The approval step at execution asks about it, and refusing it here would take
     * that choice away from the user.</p>
     *
     * @param bashCommand the line the action would run
     * @param violations  receives the refusal, if any
     * @param warnings    receives what the approval step will ask about
     * @return whether the line is refused
     */
    private boolean screenPrograms(String bashCommand, List<String> violations,
                                   List<String> warnings) {
        if (gate == null) {
            List<String> named = DangerousCommands.referencedBy(bashCommand);
            for (String token : named) {
                violations.add(REFUSED_COMMAND + "'" + token + "' is on the list of programs "
                               + "CadetCoder will not run");
            }
            return !named.isEmpty();
        }
        com.eonmux.cadetcoder.security.SecurityValidator.CommandScreening verdict =
                gate.screenCommand(bashCommand);
        if (verdict.allowed()) {
            return false;
        }
        if (verdict.reconsiderable()) {
            warnings.add("Needs approval to run: " + verdict.reason());
            return false;
        }
        violations.add(REFUSED_COMMAND + verdict.reason());
        return true;
    }

    private SecurityLevel validateFilePath(String filePath, List<String> violations, List<String> warnings) {
        SecurityLevel riskLevel = SecurityLevel.LOW;
        
        if (filePath == null) return riskLevel;
        
        // Check for system paths
        if (SYSTEM_PATH_PATTERN.matcher(filePath).find()) {
            violations.add("Access to system path not allowed: " + filePath);
            riskLevel = SecurityLevel.HIGH;
        }
        
        // Check for path traversal
        if (filePath.contains("..") || filePath.contains("~")) {
            warnings.add("Path traversal detected: " + filePath);
            if (riskLevel == SecurityLevel.LOW) {
                riskLevel = SecurityLevel.MEDIUM;
            }
        }
        
        // Check for hidden files (might contain sensitive data)
        if (filePath.contains("/.") && !filePath.endsWith("/.gitignore") && 
            !filePath.endsWith("/.gitattributes")) {
            warnings.add("Access to hidden file: " + filePath);
            if (riskLevel == SecurityLevel.LOW) {
                riskLevel = SecurityLevel.MEDIUM;
            }
        }
        
        return riskLevel;
    }
    
    private boolean isPathWithinProject(String filePath, String projectRoot) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(filePath).normalize().toAbsolutePath();
            java.nio.file.Path root = java.nio.file.Paths.get(projectRoot).normalize().toAbsolutePath();
            return path.startsWith(root);
        } catch (Exception e) {
            // If we can't resolve paths, err on the side of caution
            return false;
        }
    }
    
    /**
     * Validates the raw AI response for security issues before parsing.
     */
    public boolean isResponseSafe(String response) {
        if (response == null) return true;
        
        // Check for obvious prompt injection attempts
        if (INJECTION_PATTERN.matcher(response).find()) {
            debugLogger.warn("SecurityValidator", "Prompt injection detected in AI response");
            return false;
        }
        
        // A reply longer than anything it could legitimately be carrying is refused before
        // it is parsed, so a runaway generation costs one parse rather than a strategy each.
        if (response.length() > maxContentBytes() + ENVELOPE_ALLOWANCE) {
            debugLogger.warn("SecurityValidator", "Unusually long AI response detected");
            return false;
        }
        
        return true;
    }

    /**
     * The largest content one action may carry, as this project has configured it.
     *
     * <p>Read rather than written down, because a second copy of a limit is a second limit. The
     * response ceiling used to be a number of its own, two hundred times smaller than the one
     * a write is measured against, so a reply carrying a file the configuration plainly allows
     * was refused before it was parsed -- and refused as a security threat, which is not what
     * a long file is.</p>
     */
    private long maxContentBytes() {
        return securityConfig != null
                ? securityConfig.getMaxFileContentBytes()
                : new Configuration.SecurityConfig().getMaxFileContentBytes();
    }
    
    // Result classes
    /**
     * What the screen concluded about one action.
     *
     * <h2>Why validity is derived rather than supplied</h2>
     *
     * <p>It used to be a constructor argument, and {@code validateAction} computed it as</p>
     *
     * <pre>
     * violations.isEmpty()
     *     &amp;&amp; (riskLevel != HIGH || (securityConfig != null &amp;&amp; !securityConfig.isRequireConfirmation()))
     * </pre>
     *
     * <p>which permitted a HIGH-risk action once the user turned {@code requireConfirmation} off.
     * A setting about whether to ask the user was deciding whether the screen applies, and asking
     * for fewer prompts bought weaker screening. Nothing reached it, because every branch that
     * raises the level to HIGH also records a violation, but the rule was written down and the next
     * such branch would have inherited it.</p>
     *
     * <p>Violations are the refusal. The risk level reports how dangerous the action looks. Whether
     * to ask the user belongs to the caller that has a user to ask.</p>
     */
    public static class ValidationResult {
        private final boolean valid;
        private final SecurityLevel riskLevel;
        private final List<String> violations;
        private final List<String> warnings;
        
        /**
         * @param riskLevel  how dangerous the action looks, for reporting
         * @param violations what objected to it; one entry is enough to refuse
         * @param warnings   what is worth saying without refusing
         */
        public ValidationResult(SecurityLevel riskLevel,
                              List<String> violations, List<String> warnings) {
            this.riskLevel = riskLevel;
            this.violations = new ArrayList<>(violations);
            this.warnings = new ArrayList<>(warnings);
            this.valid = this.violations.isEmpty();
        }
        
        public boolean isValid() { return valid; }
        public SecurityLevel getRiskLevel() { return riskLevel; }
        public List<String> getViolations() { return new ArrayList<>(violations); }
        public List<String> getWarnings() { return new ArrayList<>(warnings); }
        
        public boolean hasViolations() { return !violations.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("SecurityValidation{valid=").append(valid)
              .append(", risk=").append(riskLevel);
            if (hasViolations()) {
                sb.append(", violations=").append(violations.size());
            }
            if (hasWarnings()) {
                sb.append(", warnings=").append(warnings.size());
            }
            sb.append("}");
            return sb.toString();
        }
    }
    
    public enum SecurityLevel {
        LOW,      // Safe to execute
        MEDIUM,   // Caution advised, may require confirmation
        HIGH      // Dangerous, should be blocked or require explicit confirmation
    }
}
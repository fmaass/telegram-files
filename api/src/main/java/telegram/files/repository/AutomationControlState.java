package telegram.files.repository;

/**
 * Control state for automation execution.
 * This is separate from AutomationState which tracks completion phases.
 * 
 * STOPPED (0): Automation is stopped and will not run
 * IDLE (1): Automation is enabled but waiting (no pending files or paused)
 * ACTIVE (2): Automation is actively running and processing files
 */
public enum AutomationControlState {
    STOPPED(0, "STOPPED"),
    IDLE(1, "IDLE"),
    ACTIVE(2, "ACTIVE");
    
    private final int value;
    private final String text;
    
    AutomationControlState(int value, String text) {
        this.value = value;
        this.text = text;
    }
    
    public int getValue() {
        return value;
    }
    
    public String getText() {
        return text;
    }
    
    /**
     * Convert integer value to enum.
     * Validates value and returns STOPPED for invalid values.
     */
    public static AutomationControlState fromValue(int value) {
        for (AutomationControlState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        // Invalid value - default to STOPPED and log warning
        System.err.println("WARNING: Invalid automation control state value: " + value + ". Defaulting to STOPPED.");
        return STOPPED;
    }
    
    /**
     * Convert integer value to enum, with fallback.
     */
    public static AutomationControlState fromValue(int value, AutomationControlState fallback) {
        for (AutomationControlState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return fallback;
    }
    
    /**
     * Check if state is valid.
     */
    public static boolean isValid(int value) {
        return value >= 0 && value <= 2;
    }
    
    /**
     * Auto-correct invalid state based on enabled flag.
     * If enabled=true and state is invalid/STOPPED, return IDLE.
     * If enabled=false, return STOPPED.
     */
    public static AutomationControlState autoCorrect(int stateValue, boolean enabled) {
        if (!enabled) {
            return STOPPED;
        }
        
        AutomationControlState state = fromValue(stateValue);
        if (state == STOPPED && enabled) {
            // Auto-transition to IDLE if enabled but stopped
            return IDLE;
        }
        return state;
    }
}



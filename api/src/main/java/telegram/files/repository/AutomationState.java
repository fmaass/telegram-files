package telegram.files.repository;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enum-based state machine for automation phases.
 * Each state represents a completed phase of automation.
 */
public enum AutomationState {
    /**
     * History preload phase completed
     */
    HISTORY_PRELOAD_COMPLETE,
    
    /**
     * History download scan phase completed
     */
    HISTORY_DOWNLOAD_SCAN_COMPLETE,
    
    /**
     * History download phase completed (all files downloaded)
     */
    HISTORY_DOWNLOAD_COMPLETE,
    
    /**
     * History transfer phase completed
     */
    HISTORY_TRANSFER_COMPLETE;
    
    /**
     * Convert legacy bitwise state integer to enum set.
     * 
     * @param legacyState Legacy bitwise state integer
     * @return Set of completed states
     */
    public static Set<AutomationState> fromLegacyState(int legacyState) {
        EnumSet<AutomationState> states = EnumSet.noneOf(AutomationState.class);
        
        // Map legacy bit positions to enum values
        // Note: The legacy constants (1, 2, 3, 4) are used as bit positions, not bit masks
        // HISTORY_PRELOAD_STATE = 1 -> bit position 1 (value 2)
        if ((legacyState & (1 << SettingAutoRecords.HISTORY_PRELOAD_STATE)) != 0) {
            states.add(HISTORY_PRELOAD_COMPLETE);
        }
        // HISTORY_DOWNLOAD_STATE = 2 -> bit position 2 (value 4)
        if ((legacyState & (1 << SettingAutoRecords.HISTORY_DOWNLOAD_STATE)) != 0) {
            states.add(HISTORY_DOWNLOAD_COMPLETE);
        }
        // HISTORY_DOWNLOAD_SCAN_STATE = 3 -> bit position 3 (value 8)
        if ((legacyState & (1 << SettingAutoRecords.HISTORY_DOWNLOAD_SCAN_STATE)) != 0) {
            states.add(HISTORY_DOWNLOAD_SCAN_COMPLETE);
        }
        // HISTORY_TRANSFER_STATE = 4 -> bit position 4 (value 16)
        if ((legacyState & (1 << SettingAutoRecords.HISTORY_TRANSFER_STATE)) != 0) {
            states.add(HISTORY_TRANSFER_COMPLETE);
        }
        
        return states;
    }
    
    /**
     * Convert enum set to legacy bitwise state integer.
     * For backward compatibility with existing JSON/database storage.
     * 
     * @param states Set of completed states
     * @return Legacy bitwise state integer
     */
    public static int toLegacyState(Set<AutomationState> states) {
        int legacyState = 0;
        
        for (AutomationState state : states) {
            switch (state) {
                case HISTORY_PRELOAD_COMPLETE:
                    legacyState |= (1 << SettingAutoRecords.HISTORY_PRELOAD_STATE);
                    break;
                case HISTORY_DOWNLOAD_COMPLETE:
                    legacyState |= (1 << SettingAutoRecords.HISTORY_DOWNLOAD_STATE);
                    break;
                case HISTORY_DOWNLOAD_SCAN_COMPLETE:
                    legacyState |= (1 << SettingAutoRecords.HISTORY_DOWNLOAD_SCAN_STATE);
                    break;
                case HISTORY_TRANSFER_COMPLETE:
                    legacyState |= (1 << SettingAutoRecords.HISTORY_TRANSFER_STATE);
                    break;
            }
        }
        
        return legacyState;
    }
    
    /**
     * Convert enum set to comma-separated string for JSON storage.
     * 
     * @param states Set of completed states
     * @return Comma-separated string of state names
     */
    public static String toString(Set<AutomationState> states) {
        return states.stream()
            .map(Enum::name)
            .collect(Collectors.joining(","));
    }
    
    /**
     * Parse comma-separated string to enum set.
     * 
     * @param stateString Comma-separated string of state names
     * @return Set of automation states
     */
    public static Set<AutomationState> fromString(String stateString) {
        if (stateString == null || stateString.isBlank()) {
            return EnumSet.noneOf(AutomationState.class);
        }
        
        EnumSet<AutomationState> states = EnumSet.noneOf(AutomationState.class);
        String[] parts = stateString.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                states.add(AutomationState.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                // Invalid state value - log warning but continue processing valid states
                System.err.println("WARNING: Invalid automation state value ignored: " + trimmed);
            }
        }
        return states;
    }
}


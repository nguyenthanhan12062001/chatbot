package com.chatai.an.data.repository;

import com.chatai.an.data.local.prefs.PreferencesManager;

/**
 * Preserved for backwards compatibility; delegates to UnifiedModelRepository.
 */
public class SampleModelRepository extends UnifiedModelRepository {
    public SampleModelRepository(PreferencesManager prefs) {
        super(prefs);
    }
}

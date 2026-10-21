package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import com.alpas.ainativeselfmanagingxcloudplatform.workbench.Model.IntentSpec;

/** Deliberately has no execute, approve, cloud credential or shell capabilities. */
public interface IntentInterpreter {
    IntentSpec interpret(String prompt);
    String provider();
    String model();
}

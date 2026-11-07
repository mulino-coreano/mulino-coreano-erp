package com.mulinocoreano.backend.scenario;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import org.junit.jupiter.api.Tag;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@Tag("scenario")
@IncludeEngines("cucumber")
@SelectClasspathResource("scenarios")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.mulinocoreano.backend.scenario")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary")
public class ScenarioSuite {}

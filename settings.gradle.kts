rootProject.name = "homeAssistant-backend"
include("domain", "application", "configuration", "adapter-inbound", "adapter-outbound", "app")

include("integration-llm")
include("infrastructure")
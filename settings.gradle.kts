rootProject.name = "homeAssistant-backend"
include("domain", "application", "configuration", "adapter-inbound", "adapter-outbound", "app")

include("integration-llm")
include("infrastructure")
include("architecture-test")

include("feature-identity")
project(":feature-identity").projectDir = file("feature/identity")
include("feature-finance")
project(":feature-finance").projectDir = file("feature/finance")
include("feature-sourceimport")
project(":feature-sourceimport").projectDir = file("feature/sourceimport")
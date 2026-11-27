// Prüfung mit echten Daten: läuft nur in GitHub Actions (Workflow "Prüfen mit echten Daten"), nicht in der App.
// Der Workflow kopiert vorher MixLogic.kt und Text.kt aus der App nach src/app (gleiche Regeln wie in der App).
plugins { kotlin("jvm") version "2.0.21"; application }
repositories { mavenCentral() }
dependencies { implementation("org.json:json:20240303") }
sourceSets { main { kotlin.srcDir("src") } }
application { mainClass.set("de.gamingdoodz.radiofinder.SimKt") }

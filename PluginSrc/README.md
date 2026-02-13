# Endless Ember PluginSrc

Dieses Plugin-Skelett erzeugt beim Start eine EmberPress-Rezeptvorlage automatisch.

## Was es tut

- lädt/speichert `EmberPressRecipeConfig` via `withConfig(...)`
- erstellt (wenn nicht vorhanden) eine Beispiel-Datei:
  - `mods/<ModName>/EmberPressRecipes/example_recipe.json`
- Beispielrezept: `Wood_Trunk 2 -> Ingredient_Charcoal 1`

## Wichtige Hinweise

- Der genaue Weltpfad hängt von der Runtime ab.
- Fallback ist `user.dir`.
- Optional kann im Config-Feld `WorldBasePathOverride` ein fixer Basisordner gesetzt werden.

## Nächster Schritt

Sobald dein Build-Setup (Hytale API + Gradle/Maven) drin ist, Plugin kompilieren und ins Mod-JAR packen.

# EmberPress Custom Recipes

Du kannst eigene EmberPress-Rezepte pro Welt über den Save-Ordner bereitstellen.

## Speicherort (pro Welt)

Lege deine Rezeptdateien hier ab:

`<Save>/<Weltname>/mods/DarselX_EndlessEmber/EmberPressRecipes/`

Beispiel:

`MySave/MyWorld/mods/DarselX_EndlessEmber/EmberPressRecipes/my_recipe.json`

## Regel

- **Input > 0 ist Pflicht** (keine kostenlosen Loops).

## Minimalstruktur (Beispiel)

```json
{
  "Id": "Custom_EmberPress_MyRecipe",
  "Recipe": {
    "Input": [
      {
        "ItemId": "Ingredient_Charcoal",
        "Quantity": 1
      }
    ],
    "Output": [
      {
        "ItemId": "Pressed_Coal_Rod",
        "Quantity": 1
      }
    ],
    "BenchRequirement": [
      {
        "Type": "Processing",
        "Id": "EmberPress"
      }
    ],
    "TimeSeconds": 3
  }
}
```

> Hinweis: Feldnamen/Struktur müssen zum tatsächlichen Loader deines Mods passen.
> Diese Vorlage ist als Startpunkt gedacht.

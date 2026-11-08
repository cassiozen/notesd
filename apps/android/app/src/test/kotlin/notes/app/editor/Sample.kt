package notes.app.editor

/** A note exercising every construct, for manual QA in debug builds. */
const val SAMPLE_TITLE = "Weekend plan"

val SAMPLE_MARKDOWN = """
    |# Saturday
    |
    |Pick up the **bike** from the shop, then *maybe* the ~~market~~ park.
    |
    |- Groceries
    |  - Bread
    |  - Coffee beans
    |    - [the good ones](https://example.com/coffee)
    |- Laundry
    |
    |## Checklist
    |
    |- [x] Book the table
    |- [ ] Call Sam
    |  - [ ] Ask about Sunday
    |
    |1. Leave at 9
    |2. Lunch by the river
    |
    |> Rest is part of the plan.
    |
    |```sh
    |rsync -av ~/photos /backup
    |```
    |
    |Run `make deploy` when back.
""".trimMargin() + "\n"

/** Text-heavy, for judging spacing and line height: paragraphs in a row, a list between them, a quote, a picture. */
const val PROSE_TITLE = "Typography"

val PROSE_MARKDOWN = """
    |# Saturday
    |
    |Pick up the **bike** from the shop before noon, then take the long way back along the river. If it rains, swap the ride for the museum.
    |
    |Tickets are cheaper before ten, and the café on the top floor opens at nine.
    |
    |- Groceries
    |  - Bread
    |  - Coffee beans
    |- Laundry
    |
    |Back home by six.
    |
    |### Packing
    |
    |> Rest is part of the plan.
    |>
    |> Leave the laptop at home.
    |
    |![A sketch of the route](notesd:image/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?w=320&h=120)
    |
    |```sh
    |rsync -av ~/photos /backup
    |```
    |
    |Run `make deploy` when back.
""".trimMargin() + "\n"

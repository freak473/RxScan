---
name: RxScan
description: Scan a handwritten prescription → verified medication reminders. Paper, pen ink, and pharmacy green.
colors:
  fern: "#0E6E5B"
  fern-deep: "#0A4A3E"
  fern-night: "#07352C"
  fern-wash: "#E7F0EB"
  fern-mist: "#F0F5F1"
  morning-paper: "#FBF8F1"
  paper-shade: "#F4EFE3"
  ruled-line: "#E9E1CF"
  chip-paper: "#FFFDF4"
  pen-blue: "#1E3D8F"
  pen-faded: "#8A98C4"
  moss-black: "#17211C"
  sage-muted: "#5C685F"
  sage-faint: "#96A29A"
  honey-amber: "#A66A08"
  honey-wash: "#FBF3DD"
  honey-line: "#EAD9A8"
  clay-red: "#B0503F"
  clay-line: "#E7CFC7"
  white: "#FFFFFF"
typography:
  display:
    fontFamily: "Bricolage Grotesque, Hanken Grotesk, sans-serif"
    fontSize: "30px"
    fontWeight: 800
    lineHeight: 1.13
    letterSpacing: "-0.5px"
  headline:
    fontFamily: "Bricolage Grotesque, Hanken Grotesk, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.18
    letterSpacing: "-0.2px"
  title:
    fontFamily: "Bricolage Grotesque, Hanken Grotesk, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.22
  body:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.47
  label:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.29
  ink:
    fontFamily: "Kalam, cursive"
    fontSize: "15px"
    fontWeight: 700
rounded:
  chip: "8px"
  sm: "12px"
  md: "16px"
  lg: "20px"
  full: "999px"
spacing:
  chip: "10px"
  card: "16px"
  screen: "20px"
components:
  button-primary:
    backgroundColor: "{colors.fern}"
    textColor: "{colors.white}"
    rounded: "{rounded.md}"
    height: "54px"
    padding: "0 24px"
  button-ghost:
    backgroundColor: "{colors.white}"
    textColor: "{colors.fern}"
    rounded: "{rounded.md}"
    height: "48px"
    padding: "0 20px"
  ink-chip:
    backgroundColor: "{colors.chip-paper}"
    textColor: "{colors.pen-blue}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
  paper-card:
    backgroundColor: "{colors.white}"
    textColor: "{colors.moss-black}"
    rounded: "{rounded.lg}"
    padding: "{spacing.card}"
  flag-box:
    backgroundColor: "{colors.honey-wash}"
    textColor: "{colors.honey-amber}"
    rounded: "14px"
    padding: "14px"
  slot-pill:
    backgroundColor: "{colors.fern-mist}"
    textColor: "{colors.fern}"
    rounded: "{rounded.full}"
    padding: "6px 12px"
---

# Design System: RxScan

## 1. Overview

**Creative North Star: "The Friendly Scribe"**

RxScan looks like what it is: a careful, warm note-taker you hand your prescription to. The visual world is a prescription pad brought to life — soft paper backgrounds, hairline ruled borders, and the doctor's own handwriting shown in ballpoint blue exactly as it was read. The scribe never speaks over the doctor: extracted values are *quoted* (in ink, on paper chips), and everything the app itself says is set in friendly, rounded, generously sized type. The personality is encouraging and human — a companion for someone on a bad day — never a clinical instrument and never a corporate health portal.

The system explicitly rejects the blue/white gradient sameness of generic healthtech (1mg, Practo, PharmEasy templates) and the dense, form-heavy intimidation of hospital/enterprise software. Density is low by design: one job per screen, tall touch targets, roomy padding. Warmth comes from the paper-and-ink metaphor and the copy, not from decoration.

**Key Characteristics:**
- Paper-and-ink metaphor: paper surfaces, ruled hairlines, handwriting quoted in pen blue
- One accent (Deep Fern green) for everything the app does; ink blue reserved for what the doctor wrote
- Big, soft, reassuring components — built for a sick person's thumb
- Amber strictly quarantined to re-check flags; it means "please check this yourself," nothing else
- Friendly, non-advisory voice: the UI quotes the prescription, it never instructs

## 2. Colors

A warm paper ground, one deep green voice, and a strictly rationed ink blue — with amber and clay reserved for flags and errors.

### Primary
- **Deep Fern** (#0E6E5B, `Green` in code): the app's own voice — primary buttons, confirmations, selected states, chrome. Darker siblings **Fern Deep** (#0A4A3E) and **Fern Night** (#07352C) carry pressed states and dark headers; **Fern Wash** (#E7F0EB) and **Fern Mist** (#F0F5F1) are its tinted containers (confirmed bars, slot pills).

### Secondary
- **Pen Blue** (#1E3D8F, `Ink` in code): ballpoint ink. Appears **only** on content read from the prescription — handwriting in ink chips, extracted values. **Pen Faded** (#8A98C4) is its low-confidence companion.

### Tertiary
- **Honey Amber** (#A66A08) with **Honey Wash** (#FBF3DD) and **Honey Line** (#EAD9A8): re-check flags only. Amber is a question the user must answer, never decoration and never a generic "warning."
- **Clay Red** (#B0503F) with **Clay Line** (#E7CFC7): errors and destructive actions. Softened toward terracotta so failure never shouts at a sick person.

### Neutral
- **Morning Paper** (#FBF8F1): the body background — prescription-pad white, the room everything sits in.
- **Paper Shade** (#F4EFE3): recessed surfaces (steppers, wells); **Ruled Line** (#E9E1CF): hairline borders, the pad's ruling; **Chip Paper** (#FFFDF4): the brighter scrap behind handwriting chips.
- **Moss Black** (#17211C): primary text — near-black with a leaf tint, never pure black. **Sage Muted** (#5C685F): secondary text (passes 4.5:1 on Morning Paper). **Sage Faint** (#96A29A): disabled/tertiary only, never body copy.

### Named Rules
**The Ink Rule.** Pen Blue belongs to the doctor. It renders only what was read from the prescription — never buttons, links, headings, or chrome. If it isn't a quote from the paper, it isn't blue.

**The Amber Quarantine.** Honey Amber appears only on re-check flags, always paired with an **empty** input. Amber with a pre-filled suggestion is a legal violation, not a style choice.

## 3. Typography

**Display Font:** Bricolage Grotesque (with Hanken Grotesk fallback)
**Body Font:** Hanken Grotesk (with system-ui fallback)
**Ink Font:** Kalam (cursive fallback) — the handwriting face

**Character:** A friendly expressive display over a clean humanist body — warm and a little characterful, never corporate. Kalam plays the doctor's hand: bold, blue, and only ever quoting the paper. *(The Compose build currently ships system stand-ins; swap point is `ui/theme/Type.kt`.)*

### Hierarchy
- **Display** (800, 30sp, 34sp line, −0.5 tracking): screen titles ("Check what we read").
- **Headline** (700, 22sp/26sp): section headers, sheet titles.
- **Title** (700, 18–20sp): medicine names, card titles — display family.
- **Body** (400, 15sp/22sp; medium 13.5sp/19sp): all running text. Sage Muted at minimum for secondary copy, Moss Black for anything the user must read to stay safe.
- **Label** (600, 14sp; small 600, 11sp with +0.8 tracking, uppercase): buttons, chip labels, field labels.
- **Ink** (Kalam 700, 15sp, Pen Blue): raw extracted handwriting inside ink chips. Nowhere else.

### Named Rules
**The Handwriting Rule.** Kalam renders only what the scribe read — the raw extracted text in ink chips. Never headings, never UI copy, never decoration. One handwritten voice per screen is quoting; two is theming.

**The sp Rule.** All type in sp, never fixed px/dp, so the system font-size setting scales everything — this app's users are often older or unwell.

## 4. Elevation

A lifted pad: sheets of paper floating gently over the writing desk. Every card carries a soft ambient float — depth is real, not implied — while hairline ruled borders keep edges honest. The doctrine leans lifted: prefer the full card-float shadow over borderline-flat 1dp elevation (the current Compose `PaperCard` at 1dp is the floor, not the target). Overlays — sheets, dialogs, the dose sheet — cast the deep pop shadow: something temporarily picked up off the pad.

### Shadow Vocabulary
- **Card float** (`box-shadow: 0 1px 2px rgba(23,33,28,.05), 0 6px 18px rgba(23,33,28,.06)`): every resting card.
- **Raised** (`box-shadow: 0 4px 14px rgba(23,33,28,.10)`): hover/pressed lift and emphasized cards (e.g. the next due dose). *New token, added to support the lifted doctrine.*
- **Pop** (`box-shadow: 0 8px 30px rgba(10,40,32,.18)`): modals, bottom sheets, anything above the pad.

### Named Rules
**The Lifted Sheet Rule.** Depth = attention. Resting cards float, the active thing raises, overlays pop. Never stack shadows for decoration, and never a shadow without its hairline border.

## 5. Components

Big, soft, reassuring — every control sized for a sick person's thumb ("easy for the worst day"). Minimum touch target 48dp; primary actions taller.

### Buttons
- **Shape:** generously rounded (16dp radius)
- **Primary:** Deep Fern fill, white 600–700 text at 16sp, full-width, 54dp tall (design ceiling 60px). Disabled = 35% alpha fern with 70% white text — visibly asleep, never hidden.
- **Hover / Press:** press scales down a hair (transform 60ms), background deepens to Fern Deep (150ms).
- **Ghost:** white fill, 1.5dp Ruled Line-family border (#DCD5C6), Deep Fern 600 text, 48–54dp tall.
- **Quiet:** transparent, Sage Muted text, 44dp — tertiary escape hatches only.

### Chips
- **Ink chip (signature):** a scrap of Chip Paper (#FFFDF4, 8dp radius, hairline border) carrying the raw handwriting in Kalam bold, Pen Blue. Sits under a tiny uppercase label ("WE READ"). This is the trust anchor of the verify screen — the user compares our reading against the doctor's actual hand.
- **Slot pill:** Fern Mist background, Deep Fern 600 text at 12sp, full-round (999). Renders the human schedule ("Morning · Night").

### Cards / Containers
- **Corner Style:** 20dp radius
- **Background:** white on Morning Paper ground
- **Shadow Strategy:** card float + 1dp hairline Ruled Line border (see Elevation)
- **Internal Padding:** 16dp; screens pad 20dp horizontal
- **Med card states:** unverified (neutral) → flagged (contains amber flag box) → confirmed (collapsed Fern Wash bar with ✓ and Edit). Confirmation must always be an explicit tap.

### Inputs / Fields
- **Style:** white fill, 1dp Ruled Line stroke, 12dp radius, 15sp body text, roomy 14dp padding
- **Focus:** border shifts to Deep Fern with a soft fern glow
- **Flagged (signature):** inside the amber flag box — **always empty**, never pre-filled with a system value; may re-seed only the user's own prior entry. Validated Save turns the box into a "✓ Saved" state that stays visible and revisable until the card is confirmed.
- **Error:** Clay Red border + plain-words helper text

### Navigation
- Single-screen focus flow (welcome → … → today); back is the system gesture/arrow, top bar minimal with Display-family screen title. Today ⇄ Progress switch via header controls, not a tab bar. Bottom sheets (dose actions) over modals; dialogs only for decisions that must interrupt.

### Flag Box (signature component)
Honey Wash panel, Honey Line full border, 14dp radius, fade-in 250ms. Contains: plain-words question ("The strength was hard to read — please check your prescription"), an **empty** input, a validated Save, and the **Ask your doctor** share action (opens the native share sheet with a pre-written question). The flag box is the product's honesty made visible; design it warm, never alarming.

## 6. Do's and Don'ts

### Do:
- **Do** keep Pen Blue (#1E3D8F) exclusively for content read from the prescription — the Ink Rule is the brand.
- **Do** make every touch target ≥48dp and primary buttons 54dp+ full-width; this app is used by unwell, older, and low-literacy users.
- **Do** hold body text at ≥4.5:1 contrast: Moss Black (#17211C) or Sage Muted (#5C685F) on paper — never Sage Faint for running copy.
- **Do** render flagged fields as **empty inputs** with a "please re-check against your prescription" prompt, keeping the flag box visible and revisable until the card is confirmed.
- **Do** phrase everything the app says as a quote — "your prescription says…" — in friendly plain words; encouragement over scorekeeping ("3 of 4 taken today").
- **Do** use motion as state: 60ms press, 150ms state change, 250ms fade-in for appearing panels; honor the system Remove-animations setting.

### Don't:
- **Don't** drift toward the "blue/white gradient sameness of generic healthtech (1mg, Practo, PharmEasy templates)" — no blue gradients, no stock-medical iconography, no corporate hero banners.
- **Don't** build "hospital/enterprise software" density — no data-dense dashboards, no multi-field forms, no more than one job per screen.
- **Don't** pre-fill, suggest, or auto-correct a flagged value — an amber flag with a suggested value is a regulatory breach (CDSCO), not a UX shortcut.
- **Don't** use Honey Amber anywhere except re-check flags, or Clay Red for anything but errors/destructive actions.
- **Don't** set UI copy in Kalam, use imperative medical voice ("take this at night"), or display an indication the paper doesn't state.
- **Don't** use pure black, pure gray, or untinted white surfaces — every neutral carries the paper or moss tint.
- **Don't** hide the disabled verify CTA — show it asleep (35% fern) so the hard gate reads as "finish checking," not "broken button."

# GE Visual Aid

A Grand Exchange accessibility and notification assistant for RuneLite.

## Features

- **Visual overlays** — configurable pulsing highlight boxes over suggested GE actions, with separate colours for normal actions, dump alerts, modify and collect
- **Audio alerts** — beeps on action required, urgent triple beep on dump alerts, two-tone beep on offer complete
- **Discord notifications** — rich embeds for action required, offer complete, collect needed, dump alerts, idle alerts and stuck offers
- **Pushover notifications** — push notifications to your phone, with priority alerts that bypass silent mode for dump alerts
- **GE slot tracking** — live progress bars for all 8 slots, colour coded by status with quantity progress shown
- **Profit/loss tracking** — persistent history across sessions with manual reset, best flip tracking
- **Assistive technology output** — writes full GE state to a local file every tick for integration with Philips Hue lights, haptic feedback devices, screen readers and voice announcement systems

## Optional Enhancement

Enhanced action detection is available when used alongside the Flipping Copilot plugin. The plugin runs fully in GE monitoring mode without it.

## Configuration

All features are individually toggleable in the RuneLite config panel:

- File output folder (named automatically per account)
- Overlay colour, pulse speed, border thickness
- Sound volume and cooldown
- Discord webhook URL and per-event toggles
- Pushover app key, user key and silent mode bypass
- Idle alert and stuck offer thresholds
- Profit tracking and session summary

# Realistic Natural Disasters: notes for Claude sessions

**Ongoing project: read `docs/ROADMAP.md` first.** If the user says "continue", "go on", "keep going" or anything
similar, resume the roadmap at the first unchecked item under "Progress". Keep that file's checkboxes and session log
up to date, and commit and push it at every milestone (containers are wiped between sessions).

The user's standing requests:
- Make every disaster as realistic as possible (look, sound, physics).
- Iterate with in-game screenshots: run the game headless, look from many angles and distances, and compare with real
  footage.
- Send the user screenshots as you go, and send the built jar after each big milestone.
- Use parallel worktree agents and Workflows. This overrides the "single agent" rule in `docs/SESSION_GUIDE.md`.
- Keep going until the user says stop.

Quick facts:
- Minecraft 26.1.2, NeoForge 26.1.2.126, Java 25.
- Tests: `./gradlew runGameTestServer` (grep the log for `failed at`). Jar: `./gradlew build`, then `build/libs/`.
- The headless client works through `xvfb-run` and Mesa llvmpipe. There is no sound device in the container.

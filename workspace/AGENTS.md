# System Instructions for AI Studio Build Agent

## CRITICAL: FILE EDITING RULES
**NEVER** use Python scripts (`patch_*.py`, `rewrite_*.py`, etc.) or any shell scripting to edit, patch, or modify Kotlin, XML, Gradle, or any other source code files.

**WHY:** This approach is extremely prone to errors, can easily corrupt local files, and caused a massive loss of data in a previous session. 

**HOW TO EDIT FILES:**
You **MUST** exclusively use the platform's provided file editing tools:
1. `edit_file` (for single contiguous block replacements)
2. `multi_edit_file` (for multiple non-contiguous block replacements)

If you need to make structural changes, read the file first (`view_file`), understand the content, and use the provided API tools to safely replace the text. Do not invent your own patching scripts.

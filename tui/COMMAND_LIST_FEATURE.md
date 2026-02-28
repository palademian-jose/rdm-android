# Command List Feature

## Overview
Added a command list feature to the RDM TUI that allows users to select from pre-defined commands organized by categories.

## Changes Made

### 1. New Types and Structures (`src/ui.rs`)
- Added `CommandCategory` enum with four categories:
  - DeviceInfo
  - AppManagement
  - System
  - Connectivity

- Added `PredefinedCommand` struct with fields:
  - `id`: Unique identifier
  - `name`: Display name
  - `command`: Actual shell command
  - `category`: Command category
  - `requires_sudo`: Whether root is needed
  - `description`: Command description

### 2. Predefined Command Library
Added `get_predefined_commands()` function with 17 commands organized by categories:

**DeviceInfo (7 commands):**
- Get Device Properties (`getprop`)
- Get Network Info (`ip addr show`)
- Get Process List (`ps aux`)
- Get Memory Info (`cat /proc/meminfo`)
- Get CPU Info (`cat /proc/cpuinfo`)
- Get Storage Info (`df -h`)
- Get Battery Info (`dumpsys battery`)

**AppManagement (6 commands):**
- List Installed Apps (`pm list packages -3`)
- List System Apps (`pm list packages -s`)
- Force Stop App (`am force-stop <package>`)
- Clear App Data (`pm clear <package>`)
- Uninstall App (`pm uninstall <package>`)

**System (2 commands):**
- Reboot Device (`reboot`)
- Set Screen Brightness (`settings put system screen_brightness <level>`)

**Connectivity (4 commands):**
- Enable WiFi (`svc wifi enable`)
- Disable WiFi (`svc wifi disable`)
- Enable Bluetooth (`service call bluetooth_manager 6`)
- Disable Bluetooth (`service call bluetooth_manager 8`)

### 3. State Management
- Added `selected_command_index` to `AppState` for command list selection
- Added `selected_category_index` to `AppState` for category selection
- Added `CommandList` to `View` enum

### 4. Navigation and Interaction
- Added `Tab` key to toggle between manual command input and command list
- Added `←→` arrow keys to navigate between categories
- Added `↑↓` arrow keys to navigate commands within a category
- Added `Enter` to execute selected command from list
- Added `Esc` to exit command list back to manual input
- Added helper methods:
  - `handle_command_list_navigation()`: Handle up/down navigation
  - `execute_selected_command()`: Execute currently selected command
  - `get_categories()`: Get list of categories

### 5. UI Rendering
- Added `draw_command_list()` method with two-panel layout:
  - Left panel: Category list with cyan highlight
  - Right panel: Command list with `[sudo]` indicator for privileged commands

### 6. Documentation
- Updated README.md with:
  - Command list feature in TUI features
  - New keyboard shortcuts (Tab, ←→)
  - Command list usage instructions
  - Category and command descriptions

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| Tab | Toggle command list / manual input |
| ← | Navigate to previous category |
| → | Navigate to next category |
| ↑ | Navigate to previous item |
| ↓ | Navigate to next item |
| Enter | Execute selected command |
| Esc | Exit command list |

## Visual Features

- **Categories**: Displayed in left panel, highlighted in cyan
- **Commands**: Displayed in right panel with `[sudo]` tag in red for privileged commands
- **Selection**: Cyan background with black text for selected items

## Usage Flow

1. User presses `4` to go to Command view
2. User presses `Tab` to open command list
3. User navigates categories with `←→`
4. User selects command with `↑↓`
5. User presses `Enter` to execute
6. Command executes and shows output
7. User presses `Tab` or `Esc` to return to command list or manual input

## Benefits

- **Discovery**: Users can explore available commands without memorizing shell syntax
- **Organization**: Commands grouped logically by category
- **Safety**: Predefined commands reduce risk of typos
- **Efficiency**: Quick access to common operations
- **Learning**: Shows proper command syntax for manual use

## Future Enhancements

- [ ] Add custom command templates
- [ ] Allow users to add their own commands
- [ ] Command history with quick replay
- [ ] Search/filter commands
- [ ] Parameter input dialogs for commands with placeholders
- [ ] Export command list to JSON/YAML

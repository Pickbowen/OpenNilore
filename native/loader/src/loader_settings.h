#pragma once

// Loader persistent settings backed by a small JSON. The loader targets Qt6
// (Qt6::Widgets is found by CMake above), so QJsonDocument is fine here.

#include <QHash>
#include <QStringList>
#include <QVariant>

#include <string>

namespace loader_settings {

// Keys (stable across versions). Keep these strings short & clear so the
// settings JSON stays readable.
inline QString kKeyHwidSpoof()   { return QStringLiteral("hwidSpoof"); }
inline QString kKeySyscallThread(){ return QStringLiteral("syscallThread"); }
inline QString kKeyMachineGuid() { return QStringLiteral("hwid.machineGuid"); }
inline QString kKeyVolumeSerial(){ return QStringLiteral("hwid.volumeSerial"); }
inline QString kKeyMac()         { return QStringLiteral("hwid.mac"); }
inline QString kKeyComputerName(){ return QStringLiteral("hwid.computerName"); }
inline QString kKeyProductId()   { return QStringLiteral("hwid.productId"); }

// Path of the settings file. Windows: %APPDATA%\OpenNilore\settings.json
// (load_data_at_startup). Returns empty on failure.
std::string settings_path();

// Load all settings into a QHash<QString,QVariant>. Returns the parsed map
// (empty if missing/corrupt).
QHash<QString, QVariant> load_all();

// Persist the given key/value into the settings store.
void save_value(const QString& key, const QVariant& value);

// Convenience typed accessors.
bool is_hwid_spoof_enabled();
bool is_syscall_thread_enabled();
QString hwid_custom_value(const QString& key);

// For settings.json on disk, we store as a simple JSON object. On Windows we
// keep it under %APPDATA% so it moves with the user profile (what a desktop
// app would expect), not in the exe directory (which would survive uninstall).
} // namespace loader_settings
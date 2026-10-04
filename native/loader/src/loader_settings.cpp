#include "loader_settings.h"

#include <windows.h>
#include <shlobj.h>

#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QVariantMap>

#include <cstdio>

namespace loader_settings {

namespace {

QHash<QString, QVariant> g_cache;

// Compute %APPDATA%\OpenNilore\settings.json (respects FOLDERID_RoamingAppData
// via SHGetKnownFolderPath - not the legacy CSIDL fallback so it works in
// non-roaming scenarios too).
QString settings_file() {
    static const QString path = [] {
        wchar_t* raw = nullptr;
        HRESULT hr = SHGetKnownFolderPath(FOLDERID_RoamingAppData,
                                          KF_FLAG_CREATE, nullptr, &raw);
        if (FAILED(hr) || !raw) return QString();
        QString base = QString::fromWCharArray(raw);
        CoTaskMemFree(raw);
        if (base.isEmpty()) return QString();
        QDir d(base);
        if (!d.mkpath(QStringLiteral("OpenNilore"))) return QString();
        return d.filePath(QStringLiteral("OpenNilore")) +
               QStringLiteral("/settings.json");
    }();
    return path;
}

QJsonObject read_json() {
    const QString file = settings_file();
    if (file.isEmpty()) return {};
    QFile f(file);
    if (!f.open(QIODevice::ReadOnly)) return {};
    QByteArray data = f.readAll();
    QJsonParseError err{};
    QJsonDocument doc = QJsonDocument::fromJson(data, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) return {};
    return doc.object();
}

bool write_json(const QJsonObject& obj) {
    const QString file = settings_file();
    if (file.isEmpty()) return false;
    QFile f(file);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return false;
    QJsonDocument doc(obj);
    f.write(doc.toJson(QJsonDocument::Indented));
    f.close();
    return true;
}

} // namespace

std::string settings_path() {
    return settings_file().toStdString();
}

QHash<QString, QVariant> load_all() {
    if (!g_cache.isEmpty()) return g_cache;
    QJsonObject obj = read_json();
    for (auto it = obj.begin(); it != obj.end(); ++it) {
        g_cache.insert(it.key(), it.value().toVariant());
    }
    return g_cache;
}

void save_value(const QString& key, const QVariant& value) {
    QJsonObject obj = read_json();
    obj.insert(key, QJsonValue::fromVariant(value));
    if (write_json(obj)) {
        load_all();              // reload cache
        g_cache.insert(key, value);
    }
}

bool is_hwid_spoof_enabled() {
    QVariant v = load_all().value(kKeyHwidSpoof());
    return v.isValid() && v.toBool();
}

bool is_syscall_thread_enabled() {
    QVariant v = load_all().value(kKeySyscallThread());
    return v.isValid() && v.toBool();
}

QString hwid_custom_value(const QString& key) {
    QVariant v = load_all().value(key);
    return v.isValid() ? v.toString() : QString();
}

} // namespace loader_settings
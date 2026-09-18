package client.nilore.protocol.heypixel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 把加密/签名实现接进 HeyPixelProtocolRuntime 的 Id1 挑战应答。
 * keyString 已定为 challenge.challengeValue()（GCM 认证验证通过）。
 */
public final class HeyPixelId1Wiring {

    private HeyPixelId1Wiring() {
    }

    public static void configure(HeyPixelProtocolRuntime runtime) {
        HeyPixelCrypto crypto = new HeyPixelCrypto(null);
        HeyPixelSignature signature = new HeyPixelSignature();
        Id1PacketBuilder builder = new Id1PacketBuilder(
                signature, crypto,
                Id1PacketBuilder.EvidenceSampler.preserveOrder(),
                challengeValue -> challengeValue);

        runtime.configureId1(builder, (challenge, session) -> {
            crypto.setKeyString(challenge.challengeValue());

            Id1PacketBuilder.Id1Subtype subtype = mapSubtype(challenge.subtypeName());
            UUID localUuid = parseUuid(session.sdkUid());
            Id1PacketBuilder.Context context =
                    new Id1PacketBuilder.Context(localUuid, System.currentTimeMillis());

            Object payload = switch (subtype) {
                case SPRINT -> collectSprint();
                case SNEAK -> new Id1PacketBuilder.SneakEvidence(0, List.of());
                case SWIM -> new Id1PacketBuilder.SwimEvidence(0, Map.of());
                case ATTACK -> null;
            };
            return new HeyPixelProtocolRuntime.Id1BuildInput(subtype, context, payload);
        });
    }

    private static Id1PacketBuilder.Id1Subtype mapSubtype(String name) {
        if (name == null) return Id1PacketBuilder.Id1Subtype.SPRINT;
        String upper = name.toUpperCase(Locale.ROOT);
        try {
            return Id1PacketBuilder.Id1Subtype.valueOf(upper);
        } catch (IllegalArgumentException ignored) {
            try {
                int wire = Integer.parseInt(name.trim());
                for (Id1PacketBuilder.Id1Subtype s : Id1PacketBuilder.Id1Subtype.values()) {
                    if (s.wireId() == wire) return s;
                }
            } catch (NumberFormatException ignored2) {
            }
            return Id1PacketBuilder.Id1Subtype.SPRINT;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return UUID.randomUUID();
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Id1PacketBuilder.SprintEnvironment collectSprint() {
        List<Id1PacketBuilder.ModEvidence> mods = new ArrayList<>();
        try {
            for (var info : net.minecraftforge.fml.ModList.get().getMods()) {
                mods.add(new Id1PacketBuilder.ModEvidence(info.getModId(), info.getModId()));
            }
        } catch (Throwable ignored) {
        }
        return new Id1PacketBuilder.SprintEnvironment(
                mods,
                System.getProperty("user.dir", ""),
                System.getProperty("java.home", ""),
                null, null, null, null, null, null,
                List.of());
    }
}

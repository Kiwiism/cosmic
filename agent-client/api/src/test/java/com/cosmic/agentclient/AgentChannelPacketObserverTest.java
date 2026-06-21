package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChannelPacketObserverTest {
    @Test
    void npcTalkParsesMessageTypeAfterSpeakerTypeAndNpcId() {
        byte[] packet = npcTalkPacket(2100, 1, 0);

        Optional<Map<String, Object>> observed = AgentChannelPacketObserver.observe(packet, 13);

        assertThat(observed).isPresent();
        assertThat(observed.get())
                .containsEntry("event", "NPC_TALK")
                .containsEntry("speakerType", 4)
                .containsEntry("npcId", 2100)
                .containsEntry("messageType", 1)
                .containsEntry("speaker", 0);
    }

    private static byte[] npcTalkPacket(int npcId, int messageType, int speaker) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0x130);
        out.write(4);
        writeInt(out, npcId);
        out.write(messageType);
        out.write(speaker);
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}

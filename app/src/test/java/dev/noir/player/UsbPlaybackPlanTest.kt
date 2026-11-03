package dev.noir.player

import org.junit.Assert.*
import org.junit.Test

class UsbPlaybackPlanTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun descriptor(ac: Int = 2, stream: Int = 5, alternate: Int = 4, clock: Int = 19, unit: Int = 27, master: Boolean = true): ByteArray =
        bytes(9,4,ac,0,0,1,1,0x20,0) +
        bytes(8,0x24,10,clock,1,3,0,0) +
        bytes(17,0x24,2,11,1,1,0,clock,2,3,0,0,0,0,0,0,0) +
        bytes(18,0x24,6,unit,11,if(master) 12 else 0,0,0,0,if(master) 0 else 12,0,0,0,if(master) 0 else 12,0,0,0,0) +
        bytes(12,0x24,3,12,1,3,0,unit,clock,0,0,0) +
        bytes(9,4,stream,alternate,1,1,2,0x20,0) +
        bytes(16,0x24,1,11,0,1,1,0,0,0,2,3,0,0,0,0) + bytes(6,0x24,2,1,2,16)
    private fun ep(address: Int = 2, interval: Int = 1, attributes: Int = 9, packet: Int = 196) = UsbPlaybackEndpoint(5,4,address,attributes,interval,packet)
    private fun select(raw: ByteArray = descriptor(), endpoint: UsbPlaybackEndpoint = ep(), speed: Int = 3, rate: Int = 44100) =
        UsbPlaybackPlans.select(UsbAudioDescriptors.parse(raw),listOf(endpoint),rate,16,speed)

    @Test fun discoversNonDdHiFiIdsAlternatesEndpointAndMasterVolumeFromTheSignalPath() {
        val plan=select()
        assertEquals(2,plan.clock.controlInterface); assertEquals(19,plan.clock.id)
        assertEquals(27,plan.volumeUnit); assertEquals(listOf(0),plan.volumeChannels)
        assertEquals(5,plan.format.iface);assertEquals(4,plan.format.alternate);assertEquals(2,plan.endpoint.address)
    }
    @Test fun originalProfileAndStereoChannelVolumeRemainSupported() {
        val plan=UsbPlaybackPlans.select(UsbAudioDescriptors.parse(descriptor(0,1,1,6,7,false)),listOf(UsbPlaybackEndpoint(1,1,4,9,1,196)),44100,16,3)
        assertEquals(listOf(1,2),plan.volumeChannels); assertEquals(6,plan.clock.id);assertEquals(7,plan.volumeUnit)
    }
    @Test fun fullSpeedAndDifferentHighSpeedIntervalsUseTheirOwnPacketBudgets() {
        assertEquals(2,select(endpoint=ep(packet=192),speed=2,rate=48000).endpoint.address)
        assertEquals(4,select(endpoint=ep(interval=4,packet=192),rate=48000).endpoint.interval)
        for (args in listOf(Triple(ep(packet=191),2,48000),Triple(ep(interval=4,packet=191),3,48000))) {
            assertThrows(IllegalStateException::class.java) { select(endpoint=args.first,speed=args.second,rate=args.third) }
        }
    }
    @Test fun rejectsFeedbackInputAndHighBandwidthInsteadOfSendingAudioWithWrongTiming() {
        for(endpoint in listOf(ep(attributes=5),ep(address=0x82),ep(packet=0x10c4),ep(interval=5))) {
            assertThrows(IllegalStateException::class.java){select(endpoint=endpoint)}
        }
    }
    @Test fun refusesUnrelatedOrAmbiguousControlsAndNonPcm() {
        val parsed=UsbAudioDescriptors.parse(descriptor())
        val invalid=listOf(
            parsed.copy(nodes=parsed.nodes.filterNot{it.input}),
            parsed.copy(nodes=parsed.nodes+parsed.nodes.first()),
            parsed.copy(clocks=parsed.clocks.map{it.copy(id=99)}),
            parsed.copy(volumes=parsed.volumes.map{it.copy(unit=99)}),
            parsed.copy(formats=parsed.formats.map{it.copy(pcm=false)}),
            parsed.copy(formats=parsed.formats.map{it.copy(bytesPerSample=4)}),
        )
        invalid.forEach { description -> assertThrows(IllegalStateException::class.java){UsbPlaybackPlans.select(description,listOf(ep()),44100,16,3)} }
        val mixed = descriptor()
        mixed[mixed.size - 16] = 3 // PCM and PCM8 advertised together: do not guess the wire format.
        assertThrows(IllegalStateException::class.java) { select(raw = mixed) }
    }
    @Test fun asynchronousPlaybackRequiresOneExplicitFeedbackEndpointAndPacketHeadroom() {
        val description=UsbAudioDescriptors.parse(descriptor())
        val out=ep(attributes=5,packet=28)
        val feedback=UsbPlaybackEndpoint(5,4,0x83,0x11,4,4)
        val plan=UsbPlaybackPlans.select(description,listOf(out,feedback),48000,16,3)
        assertEquals(feedback,plan.feedback)
        val invalid=listOf(
            listOf(out), listOf(out.copy(maxPacket=24),feedback),
            listOf(out,feedback.copy(maxPacket=3)), listOf(out,feedback.copy(attributes=1)),
            listOf(out,feedback,feedback.copy(address=0x84)), listOf(out,feedback.copy(iface=9)),
        )
        invalid.forEach { endpoints -> assertThrows(IllegalStateException::class.java) { UsbPlaybackPlans.select(description,endpoints,48000,16,3) } }
    }
}

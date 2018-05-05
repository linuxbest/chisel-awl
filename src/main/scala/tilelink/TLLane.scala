package hbwif.tilelink

import hbwif._
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem.{PeripheryBusKey, CacheBlockBytes}

abstract class TLLane8b10b(val clientEdge: TLEdgeOut, val managerEdge: TLEdgeIn)
    (implicit val c: SerDesConfig, implicit val b: BertConfig, implicit val m: PatternMemConfig, implicit val p: Parameters) extends Lane
    with HasEncoding8b10b
    with HasBertDebug
    with HasPatternMemDebug
    with HasBitStufferDebug4Modes
    with HasBitReversalDebug
    with HasTLBidirectionalPacketizer
    with HasTLController

class GenericTLLane8b10b(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters)
    extends TLLane8b10b(clientEdge, managerEdge)(p(HbwifSerDesKey), p(HbwifBertKey), p(HbwifPatternMemKey), p)
    with HasGenericTransceiverSubsystem


abstract class HbwifModule()(implicit p: Parameters) extends LazyModule {

    val lanes = p(HbwifTLKey).numLanes
    val beatBytes = p(HbwifTLKey).beatBytes
    val cacheBlockBytes = p(CacheBlockBytes)
    val numXact = p(HbwifTLKey).numXact
    val managerAddressSet = p(HbwifTLKey).managerAddressSet
    val configAddressSets = p(HbwifTLKey).configAddressSets
    val mrtlc = p(HbwifTLKey).managerTLC
    val mtluh = p(HbwifTLKey).managerTLUH
    val ctlc = p(HbwifTLKey).clientTLC
    val cluh = p(HbwifTLKey).clientTLUH


    val clientNode = TLClientNode((0 until lanes).map { id => TLClientPortParameters(
        Seq(TLClientParameters(
            name               = s"HbwifClient$id",
            sourceId           = IdRange(0,numXact),
            supportsGet        = TransferSizes(1, cacheBlockBytes),
            supportsPutFull    = TransferSizes(1, cacheBlockBytes),
            supportsPutPartial = TransferSizes(1, cacheBlockBytes),
            supportsArithmetic = if (ctluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsLogical    = if (ctluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsHint       = if (ctluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsProbe      = if (ctlc) TransferSizes(1, cacheBlockBytes))) else TransferSizes.none,
            minLatency         = 1)
        })
    val managerNode = TLManagerNode((0 until lanes).map { id =>
        val base = managerAddressSet
        val filter = AddressSet(id * cacheBlockBytes, ~((lanes-1) * cacheBlockBytes))
        TLManagerPortParameters(Seq(TLManagerParameters(
            address            = base.intersect(filter).toList,
            resources          = (new SimpleDevice(s"HbwifManager$id",Seq())).reg("mem"),
            regionType         = if (tlc) RegionType.CACHED else RegionType.UNCACHED,
            executable         = true,
            supportsGet        = TransferSizes(1, cacheBlockBytes),
            supportsPutFull    = TransferSizes(1, cacheBlockBytes),
            supportsPutPartial = TransferSizes(1, cacheBlockBytes),
            supportsArithmetic = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsLogical    = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsHint       = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsAcquireT   = if (mtlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsAcquireB   = if (mtlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            fifoId             = Some(0))),
        beatBytes = beatBytes,
        endSinkId = 0,
        minLatency = 1) })
    val configNodes = (0 until lanes).map { id => TLRegisterNode(
            address            = List(configAddressSets(id)),
            device             = new SimpleDevice(s"HbwifConfig$id", Seq(s"ucb-bar,hbwif$id")),
            beatBytes          = p(PeripheryBusKey).beatBytes
    )}

    lazy val module = new LazyModuleImp(this) {
        val banks = p(HbwifTLKey).numBanks
        require(lanes % banks == 0)

        // These go to clock receivers
        val hbwifRefClocks = IO(Input(Vec(banks, Clock())))
        // These go to mmio registers
        val hbwifResets = IO(Input(Vec(lanes, Bool())))

        val tx = IO(Vec(lanes, new Differential()))
        val rx = IO(Vec(lanes, Flipped(new Differential())))

        val (laneModules, addrmaps) = (0 until lanes).map({ id =>
            val (clientOut, clientEdge) = clientNode.out(id)
            val (managerIn, managerEdge) = managerNode.in(id)
            val (configIn, configEdge) = configNodes(id).in(0)
            clientOut.suggestName(s"hbwif_client_port_$id")
            managerIn.suggestName(s"hbwif_manager_port_$id")
            configIn.suggestName(s"hbwif_config_port_$id")
            val lane = Module(genLane(clientEdge, managerEdge))
            val regmap = lane.regmap
            val addrmap = TLController.toAddrmap(regmap)
            configNodes(id).regmap(regmap:_*)
            clientOut <> lane.io.data.client
            lane.io.data.manager <> managerIn
            tx(id) <> lane.io.tx
            lane.io.rx <> rx(id)
            lane.io.clockRef <> hbwifRefClocks(id/(lanes/banks))
            lane.io.asyncResetIn <> hbwifResets(id)
            (lane, addrmap)
        }).unzip
    }

    def genLane(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters): TLLane8b10b
}

class GenericHbwifModule()(implicit p: Parameters) extends HbwifModule()(p) {

    def genLane(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters) = {
        new GenericTLLane8b10b(clientEdge, managerEdge)(p)
    }

}

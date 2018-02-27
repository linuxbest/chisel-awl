package hbwif2

import chisel3._
import chisel3.util._
import scala.math.max

abstract class DecodedSymbol extends Bundle {

    val decodedWidth: Int
    val encodedWidth: Int
    val rate: Int // rate is how many decoded symbols are there per encoded symbol
    final val bits = UInt(decodedWidth.W)

    // Comparison
    def ===(other: DecodedSymbol): Bool = {
        if (this.decodedWidth == other.decodedWidth) {
            this.toBits === other.toBits
        } else {
            false.B
        }
    }

    // Define a minimum set of control symbols that must be implemented
    def comma: DecodedSymbol
    def ack: DecodedSymbol
    def nack: DecodedSymbol
    def sync: DecodedSymbol

    // How to convert data to/from (NOT the encoding, how do we convert this type to a data UInt)
    def fromData(d: UInt): DecodedSymbol
    def isData: Bool
}

abstract class Encoder extends Module {

    type DecodedSymbolType <: DecodedSymbol
    val symbolFactory: () => DecodedSymbolType

    val decodedSymbolsPerCycle: Int
    require(decodedSymbolsPerCycle >= 1, "Cannot have 0- or negative-width Encoder")

    final val encodedWidth = max(1, decodedSymbolsPerCycle / symbolFactory().rate) * symbolFactory().encodedWidth

    final val io = IO(new Bundle {
        val encoded = UInt(encodedWidth.W)
        val next = Input(Bool())
        val decoded = Input(Vec(decodedSymbolsPerCycle, Decoupled(symbolFactory())))
    })

}

abstract class Decoder extends Module {

    type DecodedSymbolType <: DecodedSymbol
    val symbolFactory: () => DecodedSymbolType

    val decodedSymbolsPerCycle: Int
    require(decodedSymbolsPerCycle >= 1, "Cannot have 0- or negative-width Decoder")

    final val encodedWidth = max(1, decodedSymbolsPerCycle / symbolFactory().rate) * symbolFactory().encodedWidth

    final val io = IO(new Bundle {
        val encoded = Valid(UInt(encodedWidth.W))
        val decoded = Output(Vec(decodedSymbolsPerCycle, Valid(symbolFactory())))
    })

}

class EncoderWidthAdapter(val enqBits: Int, val deqBits: Int) extends Module {

    val numStates = Encoding.lcm(enqBits, deqBits) / deqBits

    // Assume that ENQ always has valid data we can consume
    val io = IO(new Bundle {
        val enq = Input(UInt(enqBits.W))
        val next = Output(Bool())
        val deq = Valid(UInt(deqBits.W))
    })

    if (enqBits == deqBits) {
        io.deq := io.enq
        io.next := true.B
    } else {
        require(enqBits > deqBits, "Cannot have more deqBits than enqBits for the Encoder")
        val state = RegInit(0.U(log2Ceil(numStates).W))
        val buf = Reg(UInt((2*enqBits - 1).W)) // This can be reduced in some cases, but it should get optimized away
        io.deq := buf(deqBits - 1, 0)
        var rem = 0
        (0 until numStates) foreach { x =>
            when (state === x.U) {
                rem = rem + enqBits - deqBits
                if (rem >= deqBits) {
                    rem = rem - enqBits
                    io.next := false.B
                    buf := Cat(buf(2*enqBits-2, deqBits), buf(2*deqBits-1, deqBits))
                } else {
                    io.next := true.B
                    buf := Cat(buf(2*enqBits-2, enqBits-1-rem-deqBits), io.enq,buf(deqBits+rem-1, deqBits))

                }
                state := ((x+1) % numStates).U
            }
        }
    }
}

class DecoderWidthAdapter(val enqBits: Int, val deqBits: Int) extends Module {

    val numStates = Encoding.lcm(enqBits, deqBits) / enqBits

    val io = IO(new Bundle {
        val enq = Input(UInt(enqBits.W))
        val deq = Valid(UInt(deqBits.W))
    })

    if (enqBits == deqBits) {
        io.deq.bits := io.enq
        io.deq.valid := true.B
    } else {
        require(deqBits > enqBits, "Cannot have more enqBits than deqBits for the Decoder")
        val state = RegInit(0.U(log2Ceil(numStates).W)) // TODO see note below
        val buf = Reg(UInt((deqBits + enqBits - 1).W)) // This can be reduced in some cases, but it should get optimized away
        buf := Cat(buf(deqBits - 2, 0), io.enq)
        io.deq.bits := buf(deqBits - 1, 0)
        var filled = 0
        (0 until numStates) foreach { x =>
            when (state === x.U) {
                filled = filled + enqBits
                if (filled >= deqBits) {
                    filled = filled - deqBits
                    io.deq.valid := true.B
                } else {
                    io.deq.valid := false.B
                }
                state := ((x+1) % numStates).U // TODO this is redundant with the EncoderWidthAdapter one, may be able to optimize this out
            }
        }
    }
}

object Encoding {
    def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a%b)
    def lcm(a: Int, b: Int): Int = a*b / gcd(a, b)
}


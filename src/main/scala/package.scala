
package object Chisel {
  import Chisel.Width
  implicit class fromBigIntToLiteral(val x: BigInt) extends AnyVal {
    def U: UInt = UInt(x)
    def S: SInt = SInt(x)
  }
  implicit class fromIntToLiteral(val x: Int) extends AnyVal {
    def U: UInt = UInt(BigInt(x))
    def S: SInt = SInt(BigInt(x))
  }
  implicit class fromStringToLiteral(val x: String) extends AnyVal {
    def U: UInt = UInt(x)
  }
  implicit class fromBooleanToLiteral(val x: Boolean) extends AnyVal {
    def B: Bool = Bool(x)
  }
  // Chisel3 compatibility.
  object Input {
    def apply[T<:Data](source: T): T = source.asInput()
  }
  object Output {
    def apply[T<:Data](source: T): T = source.asOutput()
  }
  object Flipped {
    def apply[T<:Data](source: T): T = source.flip()
  }
}

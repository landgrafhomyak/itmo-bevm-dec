@file:Suppress("UNUSED") @file:OptIn(ExperimentalUnsignedTypes::class)

import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.span
import kotlinx.html.js.tr
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Window
import org.w3c.dom.url.URLSearchParams


private val minusHex = Regex("^(-?)(.*)$")

/**
 * Shortcut for preparing array to parsing
 * @return [List]&lt;[String]&gt;
 */
private inline fun Iterable<String>.prepareUByte16(): List<String> = this.filter { s -> s.isNotBlank() }

/**
 * Shortcut for splitting string and preparing to parsing
 * @return [List]&lt;[String]&gt;
 */
private inline fun String.prepareUByte16(): List<String> = this.split('\n').prepareUByte16()

/**
 * Proxy class provides access to frontend
 * @param bytecode parsed bytecode
 * @param output proxy to errors output
 * @param decompiledHtmlElement HTML element access to which provides this class
 * @property bytecode bytecode to decompile
 */
class DecompilerPage private constructor(
    val bytecode: ByteCodeMapping, val output: Output, private val decompiledHtmlElement: HTMLElement
) {
    companion object {
        /**
         * Entry point to [DecompilerPage] with checking and coloring input fields
         * @param addressHtmlElement input field with address of first operation
         * @param bytecodeHtmlElement input field with encoded operations
         * @param errorsHtmlElement output node for displaying errors
         * @param decompiledHtmlElement output &lt;tbody&gt; or &lt;table&gt; tag for showing decompiled code
         * @param window browser window
         * @param firstAddress raw predefined value in [addressHtmlElement]
         * @param bytecode raw predefined value in [bytecodeHtmlElement]
         * @return [DecompilerPage] if parsing was successful otherwise null
         */
        operator fun invoke(
            addressHtmlElement: HTMLInputElement,
            bytecodeHtmlElement: HTMLElement,
            errorsHtmlElement: HTMLElement,
            decompiledHtmlElement: HTMLElement,
            window: Window,
            firstAddress: String? = null,
            bytecode: String? = null,
        ): DecompilerPage? {
            val output = Output(errorsHtmlElement)
            decompiledHtmlElement.innerHTML = ""

            val firstAddressS: String = firstAddress ?: addressHtmlElement.value
            val bytecodeR: String = bytecode ?: bytecodeHtmlElement.innerText
            window.history.replaceState(null, "", "?" + URLSearchParams().apply {
                set("firstAddress", firstAddressS)
                set("bytecode", bytecodeR)
            }.toString())


            addressHtmlElement.value = firstAddressS
            addressHtmlElement.style.background = "white"
            val firstAddressI = try {
                firstAddressS.toAddress()
            } catch (_: NumberFormatException) {
                output.error(addressHtmlElement.id, "address", Unit, "Invalid format of address")
                addressHtmlElement.style.background = "coral"
                return null
            }

            bytecodeHtmlElement.innerHTML = ""
            val bytecodeP = bytecodeR.prepareUByte16().also { l ->
                if (l.isEmpty()) {
                    output.error("No operations passed")
                    return@invoke null
                }
            }.mapIndexed { i, s ->
                lateinit var element: HTMLElement
                bytecodeHtmlElement.append {
                    element = span {
                        id = (firstAddressI + i).toHtmlIdS()
                        +s
                    }
                    br {}
                }
                try {
                    return@mapIndexed element.id to s.toUByte16()
                } catch (_: NumberFormatException) {
                    element.style.color = "red"
                    return@mapIndexed element.id to null
                }
            }.mapIndexed { i, (id, code) ->
                if (i > Address.MAX_VALUE.toInt()) {
                    output.error("Too many operations (max ${Address.MAX_VALUE}")
                    return@mapIndexed null
                }
                if (code == null) {
                    output.error(id, (firstAddressI + i).toString(16), Unit, "Invalid format of operation code")
                }
                return@mapIndexed code
            }.let { r ->
                val f = r.filterNotNull()
                if (f.size != r.size) return@invoke null
                return@let f
            }.let { f ->
                return@let ByteCodeMapping(firstAddressI, f.toTypedArray())
            }
            output.ok("Parsed successful")
            return DecompilerPage(bytecodeP, output, decompiledHtmlElement)
        }
    }


    /**
     * Collection to storing operation codes with its addresses
     * @param firstAddress address where first operation is stored
     * @param codes operations' codes
     */
    class ByteCodeMapping(
        private val firstAddress: Address, private val codes: Array<OpCode>
    ) : Iterable<ByteCodeMapping.Row> {
        /**
         * Class for iterating operations with theirs addresses
         * @property address operation's address
         * @property code operation's code
         */
        data class Row(
            val address: Address, val code: OpCode
        )


        /**
         * @param firstAddress address where first operation is stored
         * @param codes operations' codes
         */
        constructor(firstAddress: Address, codes: Collection<OpCode>) : this(firstAddress, codes.toTypedArray())

        /**
         * @param firstAddress address where first operation is stored
         * @param codes operations' codes
         */
        constructor(firstAddress: Address, vararg codes: OpCode) : this(firstAddress, codes)

        /**
         * @param firstAddress address where first operation is stored
         * @param raw string with raw operations' codes split by '\n'
         * @throws NumberFormatException if operation's code is not hex number
         */
        constructor(firstAddress: Address, raw: String) : this(firstAddress, raw.prepareUByte16())

        /**
         * @param firstAddress address where first operation is stored
         * @param raw collection of raw operations' codes
         * @throws NumberFormatException if operation's code is not hex number
         */
        constructor(firstAddress: Address, raw: Collection<String>) : this(firstAddress, raw.toTypedArray())

        /**
         * @param firstAddress address where first operation is stored
         * @param raw array of raw operations' codes
         * @throws NumberFormatException if operation's code is not hex number
         */
        constructor(firstAddress: Address, raw: Array<String>) : this(
            firstAddress, raw.map { s -> s.toUByte16() }.toTypedArray()
        )

        /**
         * Iterates over all operations with theirs addresses
         * @see Row
         */
        override operator fun iterator(): Iterator<Row> = iterator {
            this@ByteCodeMapping.codes.forEachIndexed { i, c ->
                yield(Row(this@ByteCodeMapping.firstAddress + i, c))
            }
        }

        /**
         * Gets operation's code by address
         * @param address operation's address
         * @return operation's code
         */
        operator fun get(address: Address): OpCode = this.codes[(address - this.firstAddress).toInt()]
        // operator fun get(address: Int): UShort = this[address.toUShort()]
    }

    /**
     * Proxy class provides access errors output
     * @param errorsHtmlElement wrapped HTML node
     */
    class Output(private val errorsHtmlElement: HTMLElement) {
        init {
            this.errorsHtmlElement.innerHTML = ""
        }

        /**
         * Message builder
         * @param color message color
         * @param where optional link to location
         * @param whereText link's text (must be not null if param [where] was passed)
         * @param lines message info split by '\n'
         */
        private fun print(color: String, where: HtmlId?, whereText: String?, vararg lines: String) {
            this.errorsHtmlElement.append {
                tr {
                    style = "color: $color"
                    td {
                        if (where != null) {
                            a {
                                href = "#$where"
                                +whereText!!
                            }
                        }
                    }
                    td {
                        +lines.joinToString("\n")
                    }
                }
            }
        }

        /**
         * Prints error message (red)
         * @param firstLine error message
         * @param lines optional additional lines
         */
        fun error(firstLine: String, vararg lines: String) = this.print("red", null, null, firstLine, *lines)

        /**
         * Prints error message (red)
         * @param where link to error location
         * @param whereText link's text
         * @param firstLine error message
         * @param lines optional additional lines
         */
        fun error(where: HtmlId, whereText: String, sentinel: Unit, firstLine: String, vararg lines: String) = this.print("red", where, whereText, firstLine, *lines)

        /**
         * Prints warning message (yellow)
         * @param firstLine warning message
         * @param lines optional additional lines
         */
        fun warning(firstLine: String, vararg lines: String) = this.print("#ffd400", null, null, firstLine, *lines)

        /**
         * Prints warning message (yellow)
         * @param where link to warning location
         * @param whereText link's text
         * @param firstLine warning message
         * @param lines optional additional lines
         */
        fun warning(where: HtmlId, whereText: String, sentinel: Unit, firstLine: String, vararg lines: String) = this.print("#ffd400", where, whereText, firstLine, *lines)

        /**
         * Prints info message (grey)
         * @param firstLine warning message
         * @param lines optional additional lines
         */
        fun info(firstLine: String, vararg lines: String) = this.print("grey", null, null, firstLine, *lines)

        /**
         * Prints info message (grey)
         * @param where link to info location
         * @param whereText link's text
         * @param firstLine info message
         * @param lines optional additional lines
         */
        fun info(where: HtmlId, whereText: String, sentinel: Unit, firstLine: String, vararg lines: String) = this.print("grey", where, whereText, firstLine, *lines)

        /**
         * Prints ok message (green)
         * @param firstLine ok message
         * @param lines optional additional lines
         */
        fun ok(firstLine: String, vararg lines: String) = this.print("green", null, null, firstLine, *lines)

        /**
         * Prints ok message (green)
         * @param where link to ok location
         * @param whereText link's text
         * @param firstLine ok message
         * @param lines optional additional lines
         */
        fun ok(where: HtmlId, whereText: String, sentinel: Unit, firstLine: String, vararg lines: String) = this.print("green", where, whereText, firstLine, *lines)
    }

    class DecompiledRow(
        val mnemonic: String,
        val gotoIcon: GotoIcon?,
        val argument: Argument?,
        val comment: String
    )

    fun decompileMap(transform: DecompilerPage.(ByteCodeMapping.Row) -> DecompiledRow) {
        val code: MutableMap<Address, DecompiledRow> = HashMap()
        val data: MutableSet<Address> = HashSet()
        for (row in this.bytecode) {
            val dec = transform(this, row)
            code[row.address] = dec
            if (dec.argument != null) {
                when (dec.argument) {
                    is Argument.ABSOLUTE -> data.add(dec.argument.value)
                    is Argument.OFFSET -> data.add((row.address.toInt() + dec.argument.value).toAddress())
                    is Argument.POINTER -> data.add((row.address.toInt() + dec.argument.value).toAddress())
                    is Argument.POINTER_INC -> data.add((row.address.toInt() + dec.argument.value).toAddress())
                    is Argument.POINTER_DEC -> data.add((row.address.toInt() + dec.argument.value).toAddress())
                    is Argument.STACK, is Argument.CONST -> {}
                }
            }
        }

        this.decompiledHtmlElement.append {
            val addrs: Set<Address> = code.keys union data
            println(addrs)
            for (addr in addrs.sorted()) {
                tr {
                    id = addr.toHtmlIdD()
                    classes = setOf("code")
                    td {
                        classes = setOf("hook")
                        a {
                            href = "#" + addr.toHtmlIdD()
                            +"\u00b6"
                        }
                    }
                    td {
                        classes = setOf("address")
                        val s = addr.toString(16).padStart(3, '0')
                        if (addr in code) {
                            a {
                                href = "#" + addr.toHtmlIdS()
                                +s
                            }
                        } else {
                            +s
                        }
                    }
                    td {
                        classes = setOf("value")
                        if (addr in code) {
                            +this@DecompilerPage.bytecode[addr].toString(16).padStart(4, '0')
                        } else {
                            +"0000"
                        }
                    }
                    td {
                        classes = setOf("icons")
                        val dec = (code[addr] ?: return@td)
                        if (dec.gotoIcon != null) {
                            img {
                                src = dec.gotoIcon.path
                            }
                        }
                        if (dec.argument != null) {
                            img {
                                src = dec.argument.path
                            }
                        }
                    }
                    td {
                        classes = setOf("mnemonic")
                        +(code[addr] ?: return@td).mnemonic
                    }
                    td {
                        classes = setOf("argument")
                        val arg = (code[addr]?.argument ?: return@td)
                        +when (arg) {
                            is Argument.ABSOLUTE -> "$0x${arg.value.toString(16).padStart(3, '0')}"
                            is Argument.OFFSET -> minusHex.replace(arg.value.toString(16)) { m -> "${m.groups[1]?.value ?: ""}0x${m.groups[2]?.value ?: 0}" }
                            is Argument.POINTER -> minusHex.replace(arg.value.toString(16)) { m -> "*${m.groups[1]?.value ?: ""}0x${m.groups[2]?.value ?: 0})" }
                            is Argument.POINTER_INC -> minusHex.replace(arg.value.toString(16)) { m -> "*${m.groups[1]?.value ?: ""}0x${m.groups[2]?.value ?: 0}++" }
                            is Argument.POINTER_DEC -> minusHex.replace(arg.value.toString(16)) { m -> "--*${m.groups[1]?.value ?: ""}0x${m.groups[2]?.value ?: 0}" }
                            is Argument.STACK -> minusHex.replace(arg.value.toString(16)) { m -> "SP${m.groups[1]?.value?.takeIf(String::isNotEmpty) ?: "+"}0x${m.groups[2]?.value ?: 0}" }
                            is Argument.CONST -> minusHex.replace(arg.value.toString(16)) { m -> "#${m.groups[1]?.value ?: ""}0x${m.groups[2]?.value ?: 0}" }
                        }
                    }
                    td {
                        classes = setOf("pointer")
                        val arg = (code[addr]?.argument ?: return@td)
                        val p: Address = when (arg) {
                            is Argument.ABSOLUTE -> arg.value
                            is Argument.OFFSET -> (addr.toInt() + arg.value).toAddress()
                            is Argument.POINTER -> (addr.toInt() + arg.value).toAddress()
                            is Argument.POINTER_INC -> (addr.toInt() + arg.value).toAddress()
                            is Argument.POINTER_DEC -> (addr.toInt() + arg.value).toAddress()
                            is Argument.CONST, is Argument.STACK -> return@td
                        }
                        a {
                            href = "#" + p.toHtmlIdD()
                            +"#${p.toString(16)}"
                        }

                    }
                    td {
                        classes = setOf("comment")
                    }
                }
                if ((addr + 1u).toAddress() !in addrs) {
                    tr {
                        classes = setOf("pass")
                        td {
                            colSpan = "8"
                            +"\u2022\u2022\u2022"
                        }
                    }
                }
            }
        }
        this.decompiledHtmlElement.lastElementChild?.remove()
    }
}

enum class GotoIcon(val path: String) {
    CONDITIONAL("goto/conditional.svg"),
    STRONG("goto/strong.svg")
}

sealed class Argument(val path: String) {
    class ABSOLUTE(val value: Address) : Argument("arguments/absolute.svg")
    class OFFSET(val value: Byte) : Argument("arguments/offset.svg")
    class POINTER(val value: Byte) : Argument("arguments/pointer.svg")
    class POINTER_INC(val value: Byte) : Argument("arguments/pointer-inc.svg")
    class POINTER_DEC(val value: Byte) : Argument("arguments/pointer-dec.svg")
    class STACK(val value: Byte) : Argument("arguments/stack.svg")
    class CONST(val value: Byte) : Argument("arguments/const.svg")
}

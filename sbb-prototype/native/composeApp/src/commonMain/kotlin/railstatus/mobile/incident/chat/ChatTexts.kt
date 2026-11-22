package railstatus.mobile.incident.chat

import railstatus.chat.Source
import railstatus.chat.Topic

// What the line under an answer says, decided here so it can be tested without the UI. The words themselves are
// in composeResources (strings_chat.xml, en/de/fr/it) and follow the device language; the answers follow the
// engine (the question's language, else the passenger's).

/** Where an answer came from, as the passenger is told under it. */
enum class SourceKind {
    /** "From the 17:35 update": it cites a published update ([ChatMsg.updateAt]). */
    UPDATE,
    /** "From the control centre's updates": from the published facts, without a dated update to point at. */
    UPDATES,
    /** "Not known yet · passed on": the question went to the control centre. */
    PASSED_ON,
    /** A fixed, reviewed safety text. */
    SAFETY,
    /** A fixed, reviewed general text (refunds). */
    GENERAL,
    /** Answered on the phone because the chat service didn't answer: it reached no one. */
    OFFLINE,
}

/** The source line for an answer; null for my own messages, the pending bubble and greetings. */
fun sourceKind(m: ChatMsg): SourceKind? = when {
    m.from != ChatFrom.SBB || m.pending == true -> null
    m.offline == true -> SourceKind.OFFLINE
    m.source == Source.SAFETY -> SourceKind.SAFETY
    m.source == Source.TEMPLATE || m.source == Source.LLM -> if (m.updateAt != null) SourceKind.UPDATE else SourceKind.UPDATES
    m.source == Source.NOT_KNOWN -> SourceKind.PASSED_ON
    m.source == Source.FIXED && m.topic != Topic.GREETING -> SourceKind.GENERAL
    else -> null
}

/** How many messages the conversation may have before the quick replies step aside. */
const val QUICK_REPLIES_UNTIL = 1 // the suggestions show until the first question, then get out of the way

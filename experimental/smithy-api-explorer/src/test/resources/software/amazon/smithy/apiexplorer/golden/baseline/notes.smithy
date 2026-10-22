$version: "2"

namespace example.notes

/// Stores short text notes.
service Notes {
    version: "2024-01-01"
    operations: [GetNote, PutNote]
}

/// Returns a note.
@readonly
@http(method: "GET", uri: "/notes/{id}")
operation GetNote {
    input := {
        @required
        @httpLabel
        id: NoteId
    }
    output := {
        @required
        note: Note
    }
    errors: [NoteNotFound]
}

/// Creates or replaces a note.
@idempotent
@http(method: "PUT", uri: "/notes/{id}")
operation PutNote {
    input := {
        @required
        @httpLabel
        id: NoteId

        @required
        @httpPayload
        text: NoteText
    }
}

@pattern("^[a-z0-9-]+$")
@length(min: 1, max: 64)
string NoteId

@length(max: 2000)
string NoteText

structure Note {
    @required
    id: NoteId

    @required
    text: NoteText

    title: String
}

/// The note does not exist.
@error("client")
@httpError(404)
structure NoteNotFound {
    message: String
}

$version: "2"

namespace example.notes

/// Stores short text notes.
service Notes {
    version: "2024-01-01"
    operations: [GetNote, PutNote, DeleteNote]
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

/// Deletes a note.
@idempotent
@http(method: "DELETE", uri: "/notes/{id}", code: 204)
operation DeleteNote {
    input := {
        @required
        @httpLabel
        id: NoteId
    }
    errors: [NoteNotFound]
}

@pattern("^[a-z0-9-]+$")
@length(min: 1, max: 64)
string NoteId

@length(max: 1000)
string NoteText

structure Note {
    @required
    id: NoteId

    @required
    text: NoteText
}

/// No note has the given ID.
@error("client")
@httpError(404)
structure NoteNotFound {
    message: String
}

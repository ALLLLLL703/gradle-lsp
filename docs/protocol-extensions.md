# Protocol extensions

## External documents

Definitions backed by attached source archives or decompiled declarations use
stable `gradle-lsp://source/...` URIs. Their text stays in the server's bounded
in-memory document store; the server does not create temporary source files.

The server advertises the extension through `initialize`:

```json
{
  "capabilities": {
    "experimental": {
      "gradleLsp": {
        "externalDocument": {
          "uriScheme": "gradle-lsp",
          "request": "gradle-lsp/externalDocument"
        }
      }
    }
  }
}
```

A client that receives one of these URIs sends this request:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "gradle-lsp/externalDocument",
  "params": {
    "uri": "gradle-lsp://source/<identity>/<display-name>"
  }
}
```

The result is either `null` when the handle is unknown or an object containing
`uri`, `languageId`, and `text`. Clients should present the returned text in a
read-only buffer and must not send document change notifications for it.

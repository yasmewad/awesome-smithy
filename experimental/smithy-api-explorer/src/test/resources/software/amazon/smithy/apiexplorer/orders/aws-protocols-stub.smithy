$version: "2"

// Stand-in for the aws.protocols trait so tests can detect the protocol without the
// smithy-aws-traits dependency.
namespace aws.protocols

@trait(selector: "service")
structure restJson1 {}

# Changelog

## Unreleased

- Opt JDBC class callbacks into Jolt's optional host-table domain. Runtimes
  supporting it no longer invoke JDBC class predicates for ordinary JSON
  strings, numbers or maps. JDBC-object callbacks stay live; older runtimes
  retain the existing registration behavior.

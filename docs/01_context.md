# 01. Context

## Problem

Standard 1C:EDT debugging is interactive. There is no built-in mechanism to:
- automatically record every BSL execution step to a file or database;
- expose that trace to an external tool (Vanessa, AI agent) via HTTP;
- post-process the trace to collapse loops and repeated patterns.

## Why not TJ (Technological Journal)?

ТЖ records DB-level events, not BSL source-level steps.
It cannot tell you "line 42 of module ОбщийМодуль.Процедура1".

## Solution

An Eclipse plugin that:
1. Listens to Eclipse Debug events (SUSPEND on step);
2. Reads the current BSL stack frame (module, line, procedure);
3. Writes entries asynchronously to SQLite;
4. Exposes the trace via a local HTTP MCP server;
5. Allows post-processing (loop collapsing) on demand.

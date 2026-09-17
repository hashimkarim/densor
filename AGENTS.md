# LLM Interaction Logging

Log every interaction with an LLM under:

```text
./logs/<agent>/<thread-id>/
```

Use the name of the agent that initiated the interaction for `<agent>` and the
current conversation or task thread identifier for `<thread-id>`. Each log must
record both the input sent to the LLM and the output it returned. This applies
to all LLM interactions, including calls to subagents and external models.

Treat `./` as the repository root. Create the directory when it does not yet
exist, and never write one thread's interactions into another thread's log.

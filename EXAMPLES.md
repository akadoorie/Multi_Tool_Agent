# Example tasks — outputs & traces

Eight worked examples exercising every capability of the agent: all four tools, the
`Unsupported` scope path, two tools in one turn, and a multi-turn follow-up. Each shows the
**request**, the **final answer**, and the **full trace** returned by the API.

> For the meaning of each trace field (`index` vs. `iteration`, the step `type`s, `rollup`,
> `tools_used`), see [README §5 “Data model”](README.md#5-data-model). This file focuses on the
> worked examples themselves.

## How these were captured

Every response below is a **real, unedited** capture from the running service
(`POST /task`, default model `claude-sonnet-4-6`), taken against the live tools
(Open-Meteo, Frankfurter, Wikipedia). To reproduce, start the app (see
[README §2](README.md#2-setup--run)) and run the `curl` shown with each example.

Because they hit **live** services and a probabilistic model, a re-run will differ in the
expected ways: live-data fields (weather readings, FX rates) change over time; and
`latency_ms`, token counts, cache counters, and the exact answer wording are model-dependent.
What is **stable** is the shape that matters — the task is routed to the right tool(s), the
answer is grounded in real tool output, and every step is recorded in order.

## Summary

| # | Task | Tool(s) | Iterations | Demonstrates |
|---|------|---------|:---------:|--------------|
| 1 | *What is 15% of 240?* | `calculator` | 2 | Arithmetic routed to the calculator — never computed in the model’s head |
| 2 | *What is the current weather in Tel Aviv?* | `weather` | 2 | Live current-conditions lookup (Open-Meteo) |
| 3 | *Convert 10 kilometers to miles.* | `unit_converter` | 2 | Length conversion (local factors) |
| 4 | *Convert 100 US dollars to euros.* | `unit_converter` | 2 | Currency conversion (live FX via Frankfurter) |
| 5 | *Who invented the telephone?* | `web_search` | 3 | Encyclopedic lookup; the model reformulates and searches twice (reason→act→observe self-correction) |
| 6 | *Write a haiku about the ocean.* | — | 1 | Out of scope → a `reasoning` step explains why, verdict is `Unsupported` |
| 7 | *What is 15% of 240, and … weather in Tel Aviv?* | `calculator` + `weather` | 2 | Two tools issued **in parallel** in a single iteration |
| 8 | *Weather …* → *Convert that to Fahrenheit.* | `weather`, then `unit_converter` | 2 + 2 | Multi-turn: the follow-up resolves *“that”* from the prior turn |

---

## 1. Arithmetic → `calculator`

Even trivial math is delegated to the tool, so the number is computed deterministically rather
than guessed by the model.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"What is 15% of 240?"}'
```

```json
{
  "task_id": "94d192b3-78c0-46d2-8b2b-77d759ca7404",
  "task": "What is 15% of 240?",
  "final_answer": "15% of 240 is 36.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 3379,
      "tokens": { "input_tokens": 331, "output_tokens": 58, "total_tokens": 389 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "calculator",
      "tool_input": { "expression": "0.15 * 240" },
      "tool_output": { "ok": true, "data": { "expression": "0.15 * 240", "result": 36.0 } },
      "latency_ms": 16
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["calculator"],
      "thought": "15% of 240 is 36.",
      "latency_ms": 1314,
      "tokens": { "input_tokens": 425, "output_tokens": 13, "total_tokens": 438 }
    }
  ],
  "rollup": {
    "total_latency_ms": 4729,
    "total_input_tokens": 756,
    "total_output_tokens": 71,
    "iterations": 2,
    "total_cache_read_input_tokens": 1665,
    "total_cache_creation_input_tokens": 1665
  },
  "conversation_id": "dddfb346-084d-4aa3-b84c-53ff17d9d151",
  "conversation_order": 0
}
```

---

## 2. Current weather → `weather`

A current-conditions question is anchored to “now” and served from Open-Meteo; the answer is a
plain synthesis of the tool’s structured data.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"What is the current weather in Tel Aviv?"}'
```

```json
{
  "task_id": "03e0c235-e905-4d5b-8fd9-f147b9aa91af",
  "task": "What is the current weather in Tel Aviv?",
  "final_answer": "Here is the current weather in Tel Aviv:\n\nTemperature: 27.2°C\nHumidity: 73%\nWind Speed: 5.0 km/h",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 1679,
      "tokens": { "input_tokens": 331, "output_tokens": 53, "total_tokens": 384 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "weather",
      "tool_input": { "city": "Tel Aviv" },
      "tool_output": {
        "ok": true,
        "data": {
          "city": "Tel Aviv",
          "country": "Israel",
          "temperature_c": 27.2,
          "relative_humidity_pct": 73,
          "wind_speed_kmh": 5.0,
          "observed_at": "2026-07-04T21:15",
          "source": "open-meteo.com"
        }
      },
      "latency_ms": 690
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["weather"],
      "thought": "Here is the current weather in Tel Aviv:\n\nTemperature: 27.2°C\nHumidity: 73%\nWind Speed: 5.0 km/h",
      "latency_ms": 1942,
      "tokens": { "input_tokens": 465, "output_tokens": 40, "total_tokens": 505 }
    }
  ],
  "rollup": {
    "total_latency_ms": 4313,
    "total_input_tokens": 796,
    "total_output_tokens": 93,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "f7fcda50-80b7-472d-8ae2-dfa98cdb7674",
  "conversation_order": 0
}
```

---

## 3. Unit conversion (length) → `unit_converter`

The model maps “kilometers → miles” onto the tool’s `length` category with unit symbols; the
factor math happens in the tool.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"Convert 10 kilometers to miles."}'
```

```json
{
  "task_id": "fbf64e29-18c7-4e1c-8ec0-6d0df36099fa",
  "task": "Convert 10 kilometers to miles.",
  "final_answer": "10 kilometers is equal to 6.2137119223733395 miles.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 1952,
      "tokens": { "input_tokens": 330, "output_tokens": 105, "total_tokens": 435 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "unit_converter",
      "tool_input": { "category": "length", "value": 10, "from": "km", "to": "mi" },
      "tool_output": {
        "ok": true,
        "data": { "value": 10.0, "from": "km", "to": "mi", "result": 6.2137119223733395 }
      },
      "latency_ms": 0
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["unit_converter"],
      "thought": "10 kilometers is equal to 6.2137119223733395 miles.",
      "latency_ms": 1557,
      "tokens": { "input_tokens": 480, "output_tokens": 21, "total_tokens": 501 }
    }
  ],
  "rollup": {
    "total_latency_ms": 3512,
    "total_input_tokens": 810,
    "total_output_tokens": 126,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "cb7cdc4b-ae39-436e-8b64-57755757dde6",
  "conversation_order": 0
}
```

---

## 4. Currency conversion → `unit_converter` (live FX)

The same tool covers the `currency` category, fetching a live rate from Frankfurter — note
`"source": "frankfurter.app"` in the tool output.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"Convert 100 US dollars to euros."}'
```

```json
{
  "task_id": "5ceace6d-f248-4bfc-852d-6bef7d5ed6a0",
  "task": "Convert 100 US dollars to euros.",
  "final_answer": "100 US dollars is equal to 87.35 euros.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 2108,
      "tokens": { "input_tokens": 331, "output_tokens": 105, "total_tokens": 436 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "unit_converter",
      "tool_input": { "category": "currency", "value": 100, "from": "USD", "to": "EUR" },
      "tool_output": {
        "ok": true,
        "data": { "value": 100.0, "source": "frankfurter.app", "result": 87.35, "from": "USD", "to": "EUR" }
      },
      "latency_ms": 205
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["unit_converter"],
      "thought": "100 US dollars is equal to 87.35 euros.",
      "latency_ms": 1289,
      "tokens": { "input_tokens": 484, "output_tokens": 17, "total_tokens": 501 }
    }
  ],
  "rollup": {
    "total_latency_ms": 3604,
    "total_input_tokens": 815,
    "total_output_tokens": 122,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "ade426c4-f712-4151-9a49-4498be4fe95d",
  "conversation_order": 0
}
```

---

## 5. Factual lookup → `web_search` (multi-iteration)

A good illustration of the loop *observing* and *self-correcting*: the first search returns
Wikipedia’s generic “Telephone” page, which doesn’t answer *who invented it*, so the model
reformulates the query and searches again before answering — **three iterations, two searches**.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"Who invented the telephone?"}'
```

```json
{
  "task_id": "aa3efa5f-0d64-468a-adfd-1fb19163a2cd",
  "task": "Who invented the telephone?",
  "final_answer": "The invention of the telephone was the result of work by several individuals, most notably Alexander Graham Bell, who is most widely credited with the invention and received the first patent for it in 1876. However, other key figures were also involved in its development, including Antonio Meucci, Philipp Reis, and Elisha Gray, all of whom made significant contributions and were part of competing patent claims at the time.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 1992,
      "tokens": { "input_tokens": 327, "output_tokens": 57, "total_tokens": 384 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "web_search",
      "tool_input": { "query": "who invented the telephone" },
      "tool_output": {
        "ok": true,
        "data": {
          "query": "who invented the telephone",
          "provider": "wikipedia.org",
          "summary": "A telephone, commonly shortened to phone, is a telecommunications device that enables two or more users to conduct a conversation when they are too far apart to be easily heard directly. A telephone converts sound, typically and most efficiently the human voice, into electronic signals that are transmitted via cables and other communication channels to another telephone which reproduces the sound to the receiving user. The term is derived from Ancient Greek: τῆλε, romanized: tēle, lit. 'far' and φωνή, together meaning distant voice.",
          "sources": [
            { "title": "Telephone", "url": "https://en.wikipedia.org/wiki/Telephone" },
            { "title": "Alexander Graham Bell", "url": "https://en.wikipedia.org/wiki/Alexander_Graham_Bell" },
            { "title": "The Telephone Cases", "url": "https://en.wikipedia.org/wiki/The_Telephone_Cases" }
          ]
        }
      },
      "latency_ms": 1353
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "model_call",
      "latency_ms": 2047,
      "tokens": { "input_tokens": 607, "output_tokens": 60, "total_tokens": 667 }
    },
    {
      "index": 4,
      "iteration": 2,
      "type": "tool",
      "tool": "web_search",
      "tool_input": { "query": "Alexander Graham Bell invention of the telephone history" },
      "tool_output": {
        "ok": true,
        "data": {
          "query": "Alexander Graham Bell invention of the telephone history",
          "provider": "wikipedia.org",
          "summary": "The invention of the telephone was the culmination of work done by many different people, and led to an array of lawsuits relating to the conflicting patent claims made by several individuals and numerous companies. Notable people included in this process were Antonio Meucci, Philipp Reis, Elisha Gray and Alexander Graham Bell.",
          "sources": [
            { "title": "Invention of the telephone", "url": "https://en.wikipedia.org/wiki/Invention_of_the_telephone" },
            { "title": "Bell Telephone Company", "url": "https://en.wikipedia.org/wiki/Bell_Telephone_Company" },
            { "title": "History of the telephone", "url": "https://en.wikipedia.org/wiki/History_of_the_telephone" }
          ]
        }
      },
      "latency_ms": 1011
    },
    {
      "index": 5,
      "iteration": 3,
      "type": "final_answer",
      "tools_used": ["web_search"],
      "thought": "The invention of the telephone was the result of work by several individuals, most notably Alexander Graham Bell, who is most widely credited with the invention and received the first patent for it in 1876. However, other key figures were also involved in its development, including Antonio Meucci, Philipp Reis, and Elisha Gray, all of whom made significant contributions and were part of competing patent claims at the time.",
      "latency_ms": 2830,
      "tokens": { "input_tokens": 865, "output_tokens": 91, "total_tokens": 956 }
    }
  ],
  "rollup": {
    "total_latency_ms": 9236,
    "total_input_tokens": 1799,
    "total_output_tokens": 208,
    "iterations": 3,
    "total_cache_read_input_tokens": 4995,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "db34e660-e1c5-4862-9c16-3066b8bfbb12",
  "conversation_order": 0
}
```

---

## 6. Out of scope → `Unsupported` (with a reason)

Creative writing isn’t served by any of the four tools. The model first emits a one-sentence
**`reasoning`** step explaining *why* it’s out of scope; the system then records the verdict as
a synthetic **`final_answer`** of exactly `Unsupported` (latency 0, no tokens — it isn’t a
separate model call). The reason is also logged server-side at `WARN`.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"Write a haiku about the ocean."}'
```

```json
{
  "task_id": "f034bc65-e6b6-4cc0-9ff3-04582df0e00f",
  "task": "Write a haiku about the ocean.",
  "final_answer": "Unsupported",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "reasoning",
      "thought": "This is a creative writing task, which none of the four tools can perform.",
      "latency_ms": 1392,
      "tokens": { "input_tokens": 330, "output_tokens": 19, "total_tokens": 349 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "final_answer",
      "thought": "Unsupported",
      "latency_ms": 0
    }
  ],
  "rollup": {
    "total_latency_ms": 1394,
    "total_input_tokens": 330,
    "total_output_tokens": 19,
    "iterations": 1,
    "total_cache_read_input_tokens": 1665,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "06348cbe-8147-4535-80d5-0f98a76f6358",
  "conversation_order": 0
}
```

---

## 7. Two tools in one turn (parallel)

A task with two independent sub-questions. The model issues **both** tool calls in a single
iteration (`index` 2 and 3 share `iteration: 1`); the loop executes them and feeds both results
back, and the token rollup counts the model call once per iteration — not once per tool.

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"What is 15% of 240, and what is the current weather in Tel Aviv?"}'
```

```json
{
  "task_id": "44070177-4109-4c64-9d30-debf534a73b8",
  "task": "What is 15% of 240, and what is the current weather in Tel Aviv?",
  "final_answer": "Here are your answers:\n\n15% of 240 is 36.\n\nAs for Tel Aviv, the current temperature is 27.2°C with 73% relative humidity and a wind speed of 5 km/h.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "thought": "I'll look up both at the same time!",
      "latency_ms": 2326,
      "tokens": { "input_tokens": 341, "output_tokens": 103, "total_tokens": 444 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "calculator",
      "tool_input": { "expression": "0.15 * 240" },
      "tool_output": { "ok": true, "data": { "expression": "0.15 * 240", "result": 36.0 } },
      "latency_ms": 0
    },
    {
      "index": 3,
      "iteration": 1,
      "type": "tool",
      "tool": "weather",
      "tool_input": { "city": "Tel Aviv" },
      "tool_output": {
        "ok": true,
        "data": {
          "city": "Tel Aviv",
          "country": "Israel",
          "temperature_c": 27.2,
          "relative_humidity_pct": 73,
          "wind_speed_kmh": 5.0,
          "observed_at": "2026-07-04T21:15",
          "source": "open-meteo.com"
        }
      },
      "latency_ms": 194
    },
    {
      "index": 4,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["calculator", "weather"],
      "thought": "Here are your answers:\n\n15% of 240 is 36.\n\nAs for Tel Aviv, the current temperature is 27.2°C with 73% relative humidity and a wind speed of 5 km/h.",
      "latency_ms": 1990,
      "tokens": { "input_tokens": 596, "output_tokens": 53, "total_tokens": 649 }
    }
  ],
  "rollup": {
    "total_latency_ms": 4512,
    "total_input_tokens": 937,
    "total_output_tokens": 156,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "392eec3c-2dc4-43bf-ac54-bb03b2970199",
  "conversation_order": 0
}
```

---

## 8. Multi-turn conversation — a follow-up that references the prior turn

A follow-up task is threaded into the same conversation by calling
`POST /conversations/{conversation_id}/task` (the id goes in the **path**, not the body). Only
the prior turn’s **task + answer** is resent as context — enough for the agent to resolve
*“that temperature”* to 27.2 °C without the caller repeating it.

### Turn 1 — start a new conversation

```bash
curl -s -X POST http://localhost:8080/task \
  -H "Content-Type: application/json" \
  -d '{"task":"What is the current weather in Tel Aviv?"}'
```

```json
{
  "task_id": "1cad0655-4984-49a6-9b1e-d31006a30af6",
  "task": "What is the current weather in Tel Aviv?",
  "final_answer": "Here is the current weather in Tel Aviv:\n\nTemperature: 27.2°C\nHumidity: 73%\nWind Speed: 5.0 km/h",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 1742,
      "tokens": { "input_tokens": 331, "output_tokens": 53, "total_tokens": 384 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "weather",
      "tool_input": { "city": "Tel Aviv" },
      "tool_output": {
        "ok": true,
        "data": {
          "city": "Tel Aviv",
          "country": "Israel",
          "temperature_c": 27.2,
          "relative_humidity_pct": 73,
          "wind_speed_kmh": 5.0,
          "observed_at": "2026-07-04T21:15",
          "source": "open-meteo.com"
        }
      },
      "latency_ms": 156
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["weather"],
      "thought": "Here is the current weather in Tel Aviv:\n\nTemperature: 27.2°C\nHumidity: 73%\nWind Speed: 5.0 km/h",
      "latency_ms": 2256,
      "tokens": { "input_tokens": 465, "output_tokens": 40, "total_tokens": 505 }
    }
  ],
  "rollup": {
    "total_latency_ms": 4156,
    "total_input_tokens": 796,
    "total_output_tokens": 93,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "becd5568-bcf2-46ea-a875-33927005b527",
  "conversation_order": 0
}
```

### Turn 2 — follow-up in that conversation

The prior turn is prepended as context, so `input_tokens` on the first model call (382) is
higher than a fresh turn (~331). The agent resolves *“that temperature”* to 27.2 °C and
converts it — `conversation_order` is now `1`.

```bash
# reuse the conversation_id returned by turn 1
curl -s -X POST http://localhost:8080/conversations/becd5568-bcf2-46ea-a875-33927005b527/task \
  -H "Content-Type: application/json" \
  -d '{"task":"Convert that temperature to Fahrenheit."}'
```

```json
{
  "task_id": "e7372aed-dbb6-4587-883f-f265dc8dca02",
  "task": "Convert that temperature to Fahrenheit.",
  "final_answer": "27.2°C is equal to 80.96°F.",
  "trace": [
    {
      "index": 1,
      "iteration": 1,
      "type": "model_call",
      "latency_ms": 2642,
      "tokens": { "input_tokens": 382, "output_tokens": 107, "total_tokens": 489 }
    },
    {
      "index": 2,
      "iteration": 1,
      "type": "tool",
      "tool": "unit_converter",
      "tool_input": { "category": "temperature", "value": 27.2, "from": "C", "to": "F" },
      "tool_output": {
        "ok": true,
        "data": { "value": 27.2, "from": "C", "to": "F", "result": 80.96 }
      },
      "latency_ms": 0
    },
    {
      "index": 3,
      "iteration": 2,
      "type": "final_answer",
      "tools_used": ["unit_converter"],
      "thought": "27.2°C is equal to 80.96°F.",
      "latency_ms": 1879,
      "tokens": { "input_tokens": 529, "output_tokens": 18, "total_tokens": 547 }
    }
  ],
  "rollup": {
    "total_latency_ms": 4523,
    "total_input_tokens": 911,
    "total_output_tokens": 125,
    "iterations": 2,
    "total_cache_read_input_tokens": 3330,
    "total_cache_creation_input_tokens": 0
  },
  "conversation_id": "becd5568-bcf2-46ea-a875-33927005b527",
  "conversation_order": 1
}
```

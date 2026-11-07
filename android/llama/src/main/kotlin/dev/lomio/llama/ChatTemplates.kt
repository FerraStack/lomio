package dev.lomio.llama

/** A built-in chat template a model's GGUF template can be overridden with. */
data class TemplatePreset(val id: String, val label: String, val source: String)

/**
 * Chat template presets and a model-free formatter for previews.
 *
 * Presets cover the common families for GGUFs that ship without a template, or with a broken
 * one. The engine applies an override at load time (see [LoadParams.chatTemplate]).
 */
object ChatTemplates {
  val CHATML =
    TemplatePreset(
      "chatml",
      "ChatML (Qwen, many fine-tunes)",
      "{%- for message in messages -%}\n" +
        "{{- '<|im_start|>' + message['role'] + '\\n' + message['content'] + '<|im_end|>\\n' -}}\n" +
        "{%- endfor -%}\n" +
        "{%- if add_generation_prompt -%}{{- '<|im_start|>assistant\\n' -}}{%- endif -%}",
    )

  val LLAMA3 =
    TemplatePreset(
      "llama3",
      "Llama 3",
      "{{- bos_token -}}\n" +
        "{%- for message in messages -%}\n" +
        "{{- '<|start_header_id|>' + message['role'] + '<|end_header_id|>\\n\\n' + message['content'] | trim + '<|eot_id|>' -}}\n" +
        "{%- endfor -%}\n" +
        "{%- if add_generation_prompt -%}{{- '<|start_header_id|>assistant<|end_header_id|>\\n\\n' -}}{%- endif -%}",
    )

  val GEMMA =
    TemplatePreset(
      "gemma",
      "Gemma",
      "{{- bos_token -}}\n" +
        "{%- set ns = namespace(system='') -%}\n" +
        "{%- for message in messages -%}\n" +
        "{%- if message['role'] == 'system' -%}{%- set ns.system = message['content'] + '\\n\\n' -%}\n" +
        "{%- else -%}\n" +
        "{%- set role = 'model' if message['role'] == 'assistant' else message['role'] -%}\n" +
        "{{- '<start_of_turn>' + role + '\\n' + (ns.system if role == 'user' else '') + message['content'] | trim + '<end_of_turn>\\n' -}}\n" +
        "{%- if role == 'user' -%}{%- set ns.system = '' -%}{%- endif -%}\n" +
        "{%- endif -%}\n" +
        "{%- endfor -%}\n" +
        "{%- if add_generation_prompt -%}{{- '<start_of_turn>model\\n' -}}{%- endif -%}",
    )

  val MISTRAL =
    TemplatePreset(
      "mistral",
      "Mistral [INST]",
      "{{- bos_token -}}\n" +
        "{%- set ns = namespace(system='') -%}\n" +
        "{%- for message in messages -%}\n" +
        "{%- if message['role'] == 'system' -%}{%- set ns.system = message['content'] + '\\n\\n' -%}\n" +
        "{%- elif message['role'] == 'user' -%}{{- '[INST] ' + ns.system + message['content'] + ' [/INST]' -}}{%- set ns.system = '' -%}\n" +
        "{%- else -%}{{- ' ' + message['content'] + eos_token -}}\n" +
        "{%- endif -%}\n" +
        "{%- endfor -%}",
    )

  val PHI3 =
    TemplatePreset(
      "phi3",
      "Phi-3 / Phi-4 mini",
      "{%- for message in messages -%}\n" +
        "{{- '<|' + message['role'] + '|>\\n' + message['content'] + '<|end|>\\n' -}}\n" +
        "{%- endfor -%}\n" +
        "{%- if add_generation_prompt -%}{{- '<|assistant|>\\n' -}}{%- endif -%}",
    )

  val PRESETS = listOf(CHATML, LLAMA3, GEMMA, MISTRAL, PHI3)

  fun preset(id: String?): TemplatePreset? = PRESETS.firstOrNull { it.id == id }

  /** The conversation shown in template previews. */
  val SAMPLE =
    listOf(
      ChatMessage("system", "You are a helpful assistant."),
      ChatMessage("user", "Hello!"),
      ChatMessage("assistant", "Hi! How can I help?"),
      ChatMessage("user", "What is 2 + 2?"),
    )

  /**
   * Renders [messages] with [template] (llama.cpp's Jinja engine) as the model would see them,
   * ending with the assistant prompt. Thread-safe; throws [LlamaException] on a bad template.
   */
  fun format(
    template: String,
    messages: List<ChatMessage> = SAMPLE,
    bosToken: String = "<s>",
    eosToken: String = "</s>",
  ): String {
    NativeBridge.ensureLoaded()
    return NativeBridge.nativeFormatChat(
        template.toByteArray(),
        bosToken.toByteArray(),
        eosToken.toByteArray(),
        messages.map { it.role.toByteArray() }.toTypedArray(),
        messages.map { it.content.toByteArray() }.toTypedArray(),
        true,
      )
      .decodeToString()
  }
}

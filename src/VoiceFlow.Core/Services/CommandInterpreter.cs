using System.Globalization;
using System.Text.RegularExpressions;
using VoiceFlow.Core.Models;

namespace VoiceFlow.Core.Services;

public class CommandResult
{
    public string? Output { get; set; }
    public string? Error { get; set; }
    public bool UsedLlm { get; set; }
    public bool Success => Error == null && Output != null;
}

/// <summary>
/// Command Mode brain. A handful of deterministic transforms run locally;
/// anything free-form is delegated to the optional LLM. If no LLM is configured
/// the caller gets a friendly error instead of silent failure.
/// </summary>
public class CommandInterpreter
{
    public delegate Task<string> LlmComplete(string systemPrompt, string userPrompt, CancellationToken ct);

    private const string EditSystemPrompt =
        "You are a precise text editor. Apply the user's instruction to the provided text. " +
        "Return ONLY the transformed text — no preamble, no explanations, no quotes, no markdown fences.";

    private const string GenerateSystemPrompt =
        "You are a helpful writing assistant. Produce exactly the content the user asks for. " +
        "Return ONLY that content — no preamble, no explanations, no markdown fences.";

    public async Task<CommandResult> ExecuteAsync(
        string instruction, string selectedText, LlmComplete? llm, CancellationToken ct = default)
    {
        instruction = instruction.Trim();
        if (instruction.Length == 0)
            return new CommandResult { Error = "I didn't catch a command." };

        var hasSelection = !string.IsNullOrWhiteSpace(selectedText);

        if (hasSelection)
        {
            var local = TryLocalTransform(instruction, selectedText);
            if (local != null)
                return new CommandResult { Output = local };
        }

        if (llm == null)
        {
            return new CommandResult
            {
                Error = "That command needs AI. Add an API key in Settings → AI Commands.",
            };
        }

        try
        {
            string output = hasSelection
                ? await llm(EditSystemPrompt, $"Instruction: {instruction}\n\nText:\n{selectedText}", ct)
                : await llm(GenerateSystemPrompt, instruction, ct);
            if (string.IsNullOrWhiteSpace(output))
                return new CommandResult { Error = "The AI returned nothing. Try rephrasing." };
            return new CommandResult { Output = output, UsedLlm = true };
        }
        catch (Exception ex) when (ex is InvalidOperationException or HttpRequestException or TaskCanceledException)
        {
            Log.Error("Command LLM call failed", ex);
            return new CommandResult { Error = ex.Message };
        }
    }

    /// <summary>Deterministic transforms that work fully offline. Returns null when unmatched.</summary>
    internal static string? TryLocalTransform(string instruction, string text)
    {
        var i = instruction.ToLowerInvariant().TrimEnd('.', '!', '?');

        // Anything language-related must go to the LLM — "translate to Polish"
        // must never be shadowed by the local "polish" transform.
        if (Regex.IsMatch(i, @"\btranslat\w*\b|\bin (polish|english|spanish|french|german)\b"))
            return null;

        if (Regex.IsMatch(i, @"\b(uppercase|upper case|all caps|capitalize everything)\b"))
            return text.ToUpperInvariant();

        if (Regex.IsMatch(i, @"\b(lowercase|lower case)\b"))
            return text.ToLowerInvariant();

        if (Regex.IsMatch(i, @"\btitle case\b"))
            return CultureInfo.InvariantCulture.TextInfo.ToTitleCase(text.ToLowerInvariant());

        if (Regex.IsMatch(i, @"\b(bullet(ed)?\s+(list|points)|make (this |it )?(a |into )?bullets?)\b"))
            return string.Join("\n", SplitSentences(text).Select(s => "- " + s));

        if (Regex.IsMatch(i, @"\bnumbered list\b"))
            return string.Join("\n", SplitSentences(text).Select((s, n) => $"{n + 1}. {s}"));

        if (Regex.IsMatch(i, @"\b(remove (the )?line breaks|single paragraph|one paragraph)\b"))
            return Regex.Replace(text, @"\s*\n+\s*", " ").Trim();

        if (Regex.IsMatch(i, @"^polish$|\bpolish\s+(this|it|that|the)\b|\bclean (this |it )?up\b|\bfix (the )?(grammar|punctuation|typos)\b"))
            return TextFormatter.Format(text, CleanupLevel.High).Text;

        return null;
    }

    private static IEnumerable<string> SplitSentences(string text)
    {
        var parts = Regex.Split(text.Trim(), @"(?<=[.!?])\s+|\n+")
            .Select(p => p.Trim().TrimEnd('.'))
            .Where(p => p.Length > 0);
        return parts;
    }
}

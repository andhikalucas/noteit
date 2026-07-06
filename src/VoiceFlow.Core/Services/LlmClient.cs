using System.Text;
using System.Text.Json;

namespace VoiceFlow.Core.Services;

/// <summary>
/// Minimal client for an OpenAI-compatible chat completions endpoint. Used only for
/// Command Mode / Transforms when the user has configured their own API key.
/// </summary>
public class LlmClient
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(90),
        // A hostile/misconfigured endpoint must not be able to exhaust memory.
        MaxResponseContentBufferSize = 4_000_000,
    };

    /// <exception cref="InvalidOperationException">On configuration or API errors.</exception>
    public async Task<string> CompleteAsync(
        string baseUrl, string apiKey, string model,
        string systemPrompt, string userPrompt,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(apiKey))
            throw new InvalidOperationException("No API key configured.");
        if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException("API base URL must be HTTPS.");

        var payload = new
        {
            model,
            temperature = 0.3,
            messages = new object[]
            {
                new { role = "system", content = systemPrompt },
                new { role = "user", content = userPrompt },
            },
        };

        using var request = new HttpRequestMessage(HttpMethod.Post,
            baseUrl.TrimEnd('/') + "/chat/completions")
        {
            Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json"),
        };
        request.Headers.Add("Authorization", $"Bearer {apiKey}");

        using var response = await Http.SendAsync(request, ct).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            Log.Warn($"LLM API error {(int)response.StatusCode}");
            throw new InvalidOperationException($"AI service error ({(int)response.StatusCode}).");
        }

        using var doc = JsonDocument.Parse(body);
        var content = doc.RootElement
            .GetProperty("choices")[0]
            .GetProperty("message")
            .GetProperty("content")
            .GetString();
        return content?.Trim() ?? "";
    }
}

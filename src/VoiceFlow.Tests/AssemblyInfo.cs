using Xunit;

// E2E tests synthesize input and steal focus — they must never run concurrently.
[assembly: CollectionBehavior(DisableTestParallelization = true)]

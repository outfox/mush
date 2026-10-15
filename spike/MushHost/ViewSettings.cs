namespace Mush.Spike;

internal enum ViewMode { TwoD, ThreeD }

/// <summary>Visibility and navigation are independent, scratch-local state.</summary>
internal readonly record struct ViewSettings(bool Show2D, bool Show3D, ViewMode Mode)
{
    public string Command => $"view {(Show2D ? 1 : 0)} {(Show3D ? 1 : 0)} {(Mode == ViewMode.TwoD ? "2d" : "3d")}";

    public static ViewSettings Parse(string command)
    {
        var parts = command.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 4 || parts[0] != "view" ||
            parts[1] is not ("0" or "1") || parts[2] is not ("0" or "1") ||
            parts[3] is not ("2d" or "3d"))
            throw new ArgumentException("expected: view <2D:0|1> <3D:0|1> <move:2d|3d>");
        return new ViewSettings(parts[1] == "1", parts[2] == "1", parts[3] == "2d" ? ViewMode.TwoD : ViewMode.ThreeD);
    }
}

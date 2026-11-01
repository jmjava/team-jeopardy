using UnrealBuildTool;

public class TeamJeopardy : ModuleRules
{
	public TeamJeopardy(ReadOnlyTargetRules Target) : base(Target)
	{
		PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

		PublicDependencyModuleNames.AddRange(new string[]
		{
			"Core",
			"CoreUObject",
			"Engine",
			"InputCore",
			"Json",
			"Slate",
			"SlateCore",
			"UMG"
		});
	}
}

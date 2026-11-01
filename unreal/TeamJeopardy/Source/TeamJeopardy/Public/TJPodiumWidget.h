#pragma once

#include "Blueprint/UserWidget.h"
#include "CoreMinimal.h"
#include "TJPodiumWidget.generated.h"

class SBorder;
class STextBlock;

/** Team name and score floating above a podium. */
UCLASS()
class TEAMJEOPARDY_API UTJPodiumWidget : public UUserWidget
{
	GENERATED_BODY()

public:
	void Apply(const FString& Name, int32 Score, const FLinearColor& Color, bool bBuzzed);

protected:
	virtual TSharedRef<SWidget> RebuildWidget() override;

private:
	FString Name;
	int32 Score = 0;
	FLinearColor Color = FLinearColor::White;
	bool bBuzzed = false;
	TSharedPtr<SBorder> Frame;
	TSharedPtr<STextBlock> NameText;
	TSharedPtr<STextBlock> ScoreText;
};

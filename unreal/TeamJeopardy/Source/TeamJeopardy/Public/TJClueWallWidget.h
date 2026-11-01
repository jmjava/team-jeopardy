#pragma once

#include "Blueprint/UserWidget.h"
#include "CoreMinimal.h"
#include "TJTypes.h"
#include "Widgets/SWidget.h"
#include "TJClueWallWidget.generated.h"

class STextBlock;
class SVerticalBox;

/** Shared-display wall: teaser, prompt, buzz, and answer. The host prompt stays off this wall. */
UCLASS()
class TEAMJEOPARDY_API UTJClueWallWidget : public UUserWidget
{
	GENERATED_BODY()

public:
	void ApplyView(const FTJMatchView& InView);

protected:
	virtual TSharedRef<SWidget> RebuildWidget() override;

private:
	TSharedPtr<STextBlock> MakeLine(int32 Size, const FLinearColor& Color, bool bBold) const;

	FTJMatchView View;
	TSharedPtr<SVerticalBox> LobbyBox;
	TSharedPtr<SVerticalBox> FinishedBox;
	TSharedPtr<SVerticalBox> BannerBox;
	TSharedPtr<SVerticalBox> PreviewBox;
	TSharedPtr<SVerticalBox> PromptBox;
	TSharedPtr<SVerticalBox> BuzzBox;
	TSharedPtr<SVerticalBox> AnswerBox;
	TSharedPtr<STextBlock> LobbyTitle;
	TSharedPtr<STextBlock> CategoryText;
	TSharedPtr<STextBlock> ValueText;
	TSharedPtr<STextBlock> DailyDoubleText;
	TSharedPtr<SWidget> DailyDoubleWrap;
	TSharedPtr<STextBlock> PromptText;
	TSharedPtr<STextBlock> SourceText;
	TSharedPtr<STextBlock> BuzzText;
	TSharedPtr<STextBlock> ResponseText;
	TSharedPtr<STextBlock> ExplanationText;
	TSharedPtr<STextBlock> ScoreText;
};

#include "TJPodiumWidget.h"

#include "Styling/CoreStyle.h"
#include "TJTypes.h"
#include "Widgets/Layout/SBorder.h"
#include "Widgets/SBoxPanel.h"
#include "Widgets/Text/STextBlock.h"

TSharedRef<SWidget> UTJPodiumWidget::RebuildWidget()
{
	SAssignNew(NameText, STextBlock)
		.Justification(ETextJustify::Center)
		.Font(FCoreStyle::GetDefaultFontStyle("Bold", 18))
		.ColorAndOpacity(FSlateColor(TJPalette::Ink()));

	SAssignNew(ScoreText, STextBlock)
		.Justification(ETextJustify::Center)
		.Font(FCoreStyle::GetDefaultFontStyle("Bold", 36))
		.ColorAndOpacity(FSlateColor(TJPalette::Ink()));

	return SAssignNew(Frame, SBorder)
		.BorderImage(FCoreStyle::Get().GetBrush("WhiteBrush"))
		.BorderBackgroundColor(Color)
		.Padding(FMargin(12.f, 8.f))
		.HAlign(HAlign_Center)
		[
			SNew(SVerticalBox)
			+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
			[
				NameText.ToSharedRef()
			]
			+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
			[
				ScoreText.ToSharedRef()
			]
		];
}

void UTJPodiumWidget::Apply(const FString& InName, int32 InScore, const FLinearColor& InColor, bool bInBuzzed)
{
	Name = InName;
	Score = InScore;
	Color = InColor;
	bBuzzed = bInBuzzed;
	if (NameText.IsValid())
	{
		NameText->SetText(FText::FromString(Name.ToUpper()));
	}
	if (ScoreText.IsValid())
	{
		ScoreText->SetText(FText::FromString(TJFormatMoney(Score)));
	}
	if (Frame.IsValid())
	{
		const FLinearColor Fill = bBuzzed ? FMath::Lerp(Color, FLinearColor::White, 0.35f) : Color;
		Frame->SetBorderBackgroundColor(Fill);
	}
}

#include "TJFaceWidget.h"

#include "Styling/CoreStyle.h"
#include "Widgets/Layout/SBox.h"
#include "Widgets/Text/STextBlock.h"

TSharedRef<SWidget> UTJFaceWidget::RebuildWidget()
{
	const bool bHeader = Kind == ETJFaceKind::Category;
	SAssignNew(TextBlock, STextBlock)
		.Text(Label)
		.Justification(ETextJustify::Center)
		.AutoWrapText(bHeader)
		.WrapTextAt(bHeader ? 130.f : 0.f)
		.Font(FCoreStyle::GetDefaultFontStyle("Bold", bHeader ? 22 : 52))
		.ColorAndOpacity(FSlateColor(bHeader ? TJPalette::Text() : TJPalette::Gold()));

	return SNew(SBox)
		.HAlign(HAlign_Center)
		.VAlign(VAlign_Center)
		.Padding(FMargin(8.f))
		[
			TextBlock.ToSharedRef()
		];
}

void UTJFaceWidget::SetFace(const FText& InLabel, ETJFaceKind InKind)
{
	Label = InLabel;
	Kind = InKind;
	Refresh();
}

void UTJFaceWidget::Refresh()
{
	if (!TextBlock.IsValid())
	{
		return;
	}
	const bool bHeader = Kind == ETJFaceKind::Category;
	const bool bBlank = Kind == ETJFaceKind::Answered;
	TextBlock->SetText(bBlank ? FText::GetEmpty() : Label);
	TextBlock->SetAutoWrapText(bHeader);
	TextBlock->SetWrapTextAt(bHeader ? 130.f : 0.f);
	TextBlock->SetFont(FCoreStyle::GetDefaultFontStyle("Bold", bHeader ? 22 : 52));
	TextBlock->SetColorAndOpacity(FSlateColor(bHeader ? TJPalette::Text() : TJPalette::Gold()));
}

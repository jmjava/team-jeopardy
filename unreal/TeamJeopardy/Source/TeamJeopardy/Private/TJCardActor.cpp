#include "TJCardActor.h"

#include "Components/StaticMeshComponent.h"
#include "Components/WidgetComponent.h"
#include "TJFaceWidget.h"
#include "UObject/ConstructorHelpers.h"

ATJCardActor::ATJCardActor()
{
	PrimaryActorTick.bCanEverTick = false;

	Root = CreateDefaultSubobject<USceneComponent>(TEXT("Root"));
	SetRootComponent(Root);

	static ConstructorHelpers::FObjectFinder<UStaticMesh> Cube(TEXT("/Engine/BasicShapes/Cube.Cube"));

	Frame = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("Frame"));
	Frame->SetupAttachment(Root);
	Frame->SetMobility(EComponentMobility::Movable);
	Frame->SetRelativeScale3D(FVector(0.04f, 1.58f, 0.96f));
	Frame->SetRelativeLocation(FVector(-2.f, 0.f, 0.f));
	Frame->SetCollisionEnabled(ECollisionEnabled::NoCollision);
	Frame->SetCastShadow(false);

	Body = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("Body"));
	Body->SetupAttachment(Root);
	Body->SetMobility(EComponentMobility::Movable);
	Body->SetRelativeScale3D(FVector(0.07f, FTJStage::CardWidth / 100.f, FTJStage::CardHeight / 100.f));
	Body->SetCollisionEnabled(ECollisionEnabled::QueryOnly);
	Body->SetCollisionResponseToAllChannels(ECR_Ignore);
	Body->SetCollisionResponseToChannel(ECC_Visibility, ECR_Block);
	Body->SetCastShadow(true);

	if (Cube.Succeeded())
	{
		Frame->SetStaticMesh(Cube.Object);
		Body->SetStaticMesh(Cube.Object);
	}

	Face = CreateDefaultSubobject<UWidgetComponent>(TEXT("Face"));
	Face->SetupAttachment(Root);
	Face->SetWidgetSpace(EWidgetSpace::World);
	Face->SetWidgetClass(UTJFaceWidget::StaticClass());
	Face->SetDrawSize(FVector2D(300.f, 176.f));
	Face->SetRelativeLocation(FVector(6.f, 0.f, 0.f));
	Face->SetRelativeScale3D(FVector(0.46f));
	Face->SetPivot(FVector2D(0.5f, 0.5f));
	Face->SetTwoSided(true);
	Face->SetCollisionEnabled(ECollisionEnabled::NoCollision);
	Face->SetBlendMode(EWidgetBlendMode::Transparent);
	Face->SetBackgroundColor(FLinearColor::Transparent);
	Face->SetTintColorAndOpacity(FLinearColor::White);
}

void ATJCardActor::Setup(
	UMaterialInterface* BaseMaterial,
	const FString& InClueId,
	int32 InCategoryIndex,
	int32 InClueIndex,
	const FText& InLabel,
	ETJFaceKind InKind,
	const FLinearColor& InFaceColor,
	const FLinearColor& InFrameColor)
{
	ClueId = InClueId;
	CategorySlot = InCategoryIndex;
	ClueSlot = InClueIndex;
	ValueLabel = InLabel;
	Label = InLabel;
	Kind = InKind;
	FaceColor = InFaceColor;
	FrameColor = InFrameColor;
	bAnswered = false;
	bHighlighted = false;

	if (BaseMaterial)
	{
		FrameMaterial = UMaterialInstanceDynamic::Create(BaseMaterial, this);
		FaceMaterial = UMaterialInstanceDynamic::Create(BaseMaterial, this);
		if (Frame)
		{
			Frame->SetMaterial(0, FrameMaterial);
		}
		if (Body)
		{
			Body->SetMaterial(0, FaceMaterial);
		}
	}

	ApplyFace();
	ApplyMaterial();
}

void ATJCardActor::SetAnswered(bool bInAnswered)
{
	if (IsHeader() || bAnswered == bInAnswered)
	{
		return;
	}
	bAnswered = bInAnswered;
	if (bAnswered)
	{
		bHighlighted = false;
		Label = FText::GetEmpty();
		Kind = ETJFaceKind::Answered;
	}
	else
	{
		Label = ValueLabel;
		Kind = ETJFaceKind::Value;
	}
	ApplyFace();
	ApplyMaterial();
}

void ATJCardActor::SetHighlighted(bool bOn)
{
	if (bAnswered || IsHeader() || bHighlighted == bOn)
	{
		return;
	}
	bHighlighted = bOn;
	ApplyMaterial();
}

void ATJCardActor::ApplyFace()
{
	if (!Face)
	{
		return;
	}
	Face->InitWidget();
	if (UTJFaceWidget* Widget = Cast<UTJFaceWidget>(Face->GetUserWidgetObject()))
	{
		Widget->SetFace(Label, Kind);
		Face->RequestRenderUpdate();
	}
}

void ATJCardActor::ApplyMaterial()
{
	const FLinearColor Color = bAnswered
		? TJPalette::CellAnswered()
		: (bHighlighted ? FMath::Lerp(FaceColor, FLinearColor::White, 0.22f) : FaceColor);
	if (FaceMaterial)
	{
		FaceMaterial->SetVectorParameterValue(TEXT("Color"), Color);
	}
	if (FrameMaterial)
	{
		FrameMaterial->SetVectorParameterValue(TEXT("Color"), FrameColor);
	}
}

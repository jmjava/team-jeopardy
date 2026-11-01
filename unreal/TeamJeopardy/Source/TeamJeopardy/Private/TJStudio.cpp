#include "TJStudio.h"

#include "Components/SceneComponent.h"
#include "Components/SkyLightComponent.h"
#include "Components/SpotLightComponent.h"
#include "Components/StaticMeshComponent.h"
#include "Components/WidgetComponent.h"
#include "TJCardActor.h"
#include "TJClueWallWidget.h"
#include "TJPodiumWidget.h"
#include "UObject/ConstructorHelpers.h"

ATJStudio::ATJStudio()
{
	PrimaryActorTick.bCanEverTick = false;

	Root = CreateDefaultSubobject<USceneComponent>(TEXT("Root"));
	SetRootComponent(Root);

	static ConstructorHelpers::FObjectFinder<UStaticMesh> Cube(TEXT("/Engine/BasicShapes/Cube.Cube"));
	static ConstructorHelpers::FObjectFinder<UStaticMesh> Plane(TEXT("/Engine/BasicShapes/Plane.Plane"));
	static ConstructorHelpers::FObjectFinder<UStaticMesh> Cylinder(TEXT("/Engine/BasicShapes/Cylinder.Cylinder"));
	static ConstructorHelpers::FObjectFinder<UMaterialInterface> Material(TEXT("/Engine/BasicShapes/BasicShapeMaterial.BasicShapeMaterial"));
	if (Cube.Succeeded())
	{
		CubeMesh = Cube.Object;
	}
	if (Plane.Succeeded())
	{
		PlaneMesh = Plane.Object;
	}
	if (Cylinder.Succeeded())
	{
		CylinderMesh = Cylinder.Object;
	}
	if (Material.Succeeded())
	{
		ShapeMaterial = Material.Object;
	}

	Floor = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("Floor"));
	Floor->SetupAttachment(Root);
	Floor->SetMobility(EComponentMobility::Movable);
	Floor->SetStaticMesh(PlaneMesh);
	Floor->SetRelativeLocation(FVector(500.f, 0.f, -2.f));
	Floor->SetRelativeScale3D(FVector(50.f, 30.f, 1.f));
	Floor->SetCollisionEnabled(ECollisionEnabled::NoCollision);

	BackWall = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("BackWall"));
	BackWall->SetupAttachment(Root);
	BackWall->SetMobility(EComponentMobility::Movable);
	BackWall->SetStaticMesh(CubeMesh);
	BackWall->SetRelativeLocation(FVector(-90.f, 0.f, 520.f));
	BackWall->SetRelativeScale3D(FVector(0.4f, 24.f, 11.f));
	BackWall->SetCollisionEnabled(ECollisionEnabled::NoCollision);
	BackWall->SetCastShadow(false);

	Ceiling = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("Ceiling"));
	Ceiling->SetupAttachment(Root);
	Ceiling->SetMobility(EComponentMobility::Movable);
	Ceiling->SetStaticMesh(CubeMesh);
	Ceiling->SetRelativeLocation(FVector(700.f, 0.f, 1050.f));
	Ceiling->SetRelativeScale3D(FVector(32.f, 24.f, 0.25f));
	Ceiling->SetCollisionEnabled(ECollisionEnabled::NoCollision);
	Ceiling->SetCastShadow(false);

	KeyLight = CreateDefaultSubobject<USpotLightComponent>(TEXT("KeyLight"));
	KeyLight->SetupAttachment(Root);
	KeyLight->SetMobility(EComponentMobility::Movable);
	KeyLight->SetAttenuationRadius(6500.f);
	KeyLight->SetCastShadows(true);
	KeyLight->SetIntensity(280000.f);
	KeyLight->SetInnerConeAngle(24.f);
	KeyLight->SetOuterConeAngle(46.f);
	KeyLight->SetLightColor(FLinearColor(1.f, 0.96f, 0.9f));

	FillLight = CreateDefaultSubobject<USpotLightComponent>(TEXT("FillLight"));
	FillLight->SetupAttachment(Root);
	FillLight->SetMobility(EComponentMobility::Movable);
	FillLight->SetAttenuationRadius(6500.f);
	FillLight->SetCastShadows(true);
	FillLight->SetIntensity(90000.f);
	FillLight->SetInnerConeAngle(30.f);
	FillLight->SetOuterConeAngle(58.f);
	FillLight->SetLightColor(FLinearColor(0.65f, 0.78f, 1.f));

	GoldLight = CreateDefaultSubobject<USpotLightComponent>(TEXT("GoldLight"));
	GoldLight->SetupAttachment(Root);
	GoldLight->SetMobility(EComponentMobility::Movable);
	GoldLight->SetAttenuationRadius(6500.f);
	GoldLight->SetCastShadows(true);
	GoldLight->SetIntensity(70000.f);
	GoldLight->SetInnerConeAngle(18.f);
	GoldLight->SetOuterConeAngle(40.f);
	GoldLight->SetLightColor(TJPalette::Gold());

	Sky = CreateDefaultSubobject<USkyLightComponent>(TEXT("Sky"));
	Sky->SetupAttachment(Root);
	Sky->SetMobility(EComponentMobility::Movable);
	Sky->SourceType = SLS_CapturedScene;
	Sky->SetIntensity(1.5f);
	Sky->SetLightColor(FLinearColor(0.72f, 0.8f, 1.f));

	ClueWall = CreateDefaultSubobject<UWidgetComponent>(TEXT("ClueWall"));
	ClueWall->SetupAttachment(Root);
	ClueWall->SetWidgetSpace(EWidgetSpace::World);
	ClueWall->SetWidgetClass(UTJClueWallWidget::StaticClass());
	ClueWall->SetDrawSize(FVector2D(980.f, 620.f));
	ClueWall->SetRelativeLocation(FVector(28.f, 0.f, 450.f));
	ClueWall->SetPivot(FVector2D(0.5f, 0.5f));
	ClueWall->SetTwoSided(false);
	ClueWall->SetCollisionEnabled(ECollisionEnabled::NoCollision);
	ClueWall->SetBlendMode(EWidgetBlendMode::Transparent);
	ClueWall->SetBackgroundColor(FLinearColor::Transparent);
	ClueWall->SetVisibility(false);

	PodiumMeshes.Reserve(3);
	PodiumFaces.Reserve(3);
	const float PodiumY[3] = {-360.f, 0.f, 360.f};
	for (int32 Index = 0; Index < 3; ++Index)
	{
		const FString MeshName = FString::Printf(TEXT("PodiumMesh%d"), Index);
		UStaticMeshComponent* Mesh = CreateDefaultSubobject<UStaticMeshComponent>(*MeshName);
		Mesh->SetupAttachment(Root);
		Mesh->SetMobility(EComponentMobility::Movable);
		Mesh->SetStaticMesh(CylinderMesh);
		Mesh->SetRelativeLocation(FVector(640.f, PodiumY[Index], 55.f));
		Mesh->SetRelativeScale3D(FVector(0.9f, 0.9f, 1.1f));
		Mesh->SetCollisionEnabled(ECollisionEnabled::QueryOnly);
		Mesh->SetCollisionResponseToAllChannels(ECR_Ignore);
		Mesh->SetCollisionResponseToChannel(ECC_Visibility, ECR_Block);
		PodiumMeshes.Add(Mesh);

		const FString FaceName = FString::Printf(TEXT("PodiumFace%d"), Index);
		UWidgetComponent* Face = CreateDefaultSubobject<UWidgetComponent>(*FaceName);
		Face->SetupAttachment(Root);
		Face->SetWidgetSpace(EWidgetSpace::World);
		Face->SetWidgetClass(UTJPodiumWidget::StaticClass());
		Face->SetDrawSize(FVector2D(220.f, 120.f));
		Face->SetRelativeLocation(FVector(700.f, PodiumY[Index], 175.f));
		Face->SetPivot(FVector2D(0.5f, 0.5f));
		Face->SetTwoSided(false);
		Face->SetCollisionEnabled(ECollisionEnabled::NoCollision);
		Face->SetBlendMode(EWidgetBlendMode::Transparent);
		Face->SetBackgroundColor(FLinearColor::Transparent);
		PodiumFaces.Add(Face);
	}
}

void ATJStudio::BeginPlay()
{
	Super::BeginPlay();
	Paint(Floor, TJPalette::Background());
	Paint(BackWall, TJPalette::BoardBorder());
	Paint(Ceiling, TJPalette::Ink());
	PodiumMaterials.Reset();
	for (UStaticMeshComponent* Mesh : PodiumMeshes)
	{
		PodiumMaterials.Add(Paint(Mesh, FLinearColor::White));
	}
	AimLights();
	if (ClueWall)
	{
		ClueWall->InitWidget();
	}
	for (UWidgetComponent* Face : PodiumFaces)
	{
		if (Face)
		{
			Face->InitWidget();
		}
	}
	if (Sky)
	{
		Sky->RecaptureSky();
	}
}

void ATJStudio::AimLights()
{
	const FVector LookTarget = BoardCenter;
	auto Aim = [](USpotLightComponent* Light, const FVector& From, const FVector& To)
	{
		if (!Light)
		{
			return;
		}
		Light->SetWorldLocation(From);
		Light->SetWorldRotation((To - From).Rotation());
	};
	Aim(KeyLight, FVector(1100.f, -520.f, 1180.f), LookTarget);
	Aim(FillLight, FVector(860.f, 700.f, 860.f), LookTarget);
	Aim(GoldLight, FVector(480.f, 0.f, 30.f), FVector(0.f, 0.f, 260.f));
}

UMaterialInstanceDynamic* ATJStudio::Paint(UStaticMeshComponent* Mesh, const FLinearColor& Color)
{
	if (!Mesh || !ShapeMaterial)
	{
		return nullptr;
	}
	UMaterialInstanceDynamic* Material = UMaterialInstanceDynamic::Create(ShapeMaterial, this);
	if (!Material)
	{
		return nullptr;
	}
	Material->SetVectorParameterValue(TEXT("Color"), Color);
	Mesh->SetMaterial(0, Material);
	return Material;
}

void ATJStudio::BuildBoard(const FTJBoard& Board)
{
	if (bBoardBuilt || !GetWorld())
	{
		return;
	}

	const int32 Cols = Board.Categories.Num();
	int32 MaxClues = 0;
	for (const FTJCategory& Category : Board.Categories)
	{
		MaxClues = FMath::Max(MaxClues, Category.Clues.Num());
	}
	const int32 Rows = MaxClues + 1;
	const float TotalW = Cols * FTJStage::CardWidth + FMath::Max(0, Cols - 1) * FTJStage::Gap;
	const float TotalH = Rows * FTJStage::CardHeight + FMath::Max(0, Rows - 1) * FTJStage::Gap;
	const float Left = -TotalW * 0.5f;
	const float Top = FTJStage::BoardBottom + TotalH;
	BoardCenter = FVector(0.f, 0.f, FTJStage::BoardBottom + TotalH * 0.5f);

	FActorSpawnParameters Params;
	Params.Owner = this;
	Params.SpawnCollisionHandlingOverride = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;

	for (int32 Col = 0; Col < Cols; ++Col)
	{
		const FTJCategory& Category = Board.Categories[Col];
		const float Y = Left + FTJStage::CardWidth * 0.5f + Col * (FTJStage::CardWidth + FTJStage::Gap);
		const float HeaderZ = Top - FTJStage::CardHeight * 0.5f;
		if (ATJCardActor* Header = GetWorld()->SpawnActor<ATJCardActor>(ATJCardActor::StaticClass(), FTransform(FRotator::ZeroRotator, FVector(0.f, Y, HeaderZ)), Params))
		{
			Header->Setup(ShapeMaterial, FString(), Col, INDEX_NONE, FText::FromString(Category.Title), ETJFaceKind::Category, TJPalette::Board(), TJPalette::BoardBorder());
			Cards.Add(Header);
		}

		for (int32 ClueIndex = 0; ClueIndex < Category.Clues.Num(); ++ClueIndex)
		{
			const FTJClue& Clue = Category.Clues[ClueIndex];
			const float Z = HeaderZ - (ClueIndex + 1) * (FTJStage::CardHeight + FTJStage::Gap);
			if (ATJCardActor* Card = GetWorld()->SpawnActor<ATJCardActor>(ATJCardActor::StaticClass(), FTransform(FRotator::ZeroRotator, FVector(0.f, Y, Z)), Params))
			{
				Card->Setup(ShapeMaterial, Clue.Id, Col, ClueIndex, FText::FromString(TJFormatMoney(Clue.Value)), ETJFaceKind::Value, TJPalette::BoardCell(), TJPalette::BoardBorder());
				Cards.Add(Card);
			}
		}
	}

	if (ClueWall)
	{
		ClueWall->SetWorldLocation(BoardCenter + FVector(28.f, 0.f, 0.f));
		ClueWall->SetDrawSize(FVector2D(TotalW + 24.f, TotalH + 24.f));
	}

	bBoardBuilt = true;
	AimLights();
	if (Sky)
	{
		Sky->RecaptureSky();
	}
}

void ATJStudio::Sync(const FTJMatchView& View)
{
	for (ATJCardActor* Card : Cards)
	{
		if (!Card || Card->IsHeader())
		{
			continue;
		}
		Card->SetAnswered(View.AnsweredClueIds.Contains(Card->GetClueId()));
	}

	if (ClueWall)
	{
		const bool bShowWall = View.Phase != ETJPhase::Board;
		ClueWall->SetVisibility(bShowWall);
		if (UTJClueWallWidget* Wall = Cast<UTJClueWallWidget>(ClueWall->GetUserWidgetObject()))
		{
			Wall->ApplyView(View);
			ClueWall->RequestRedraw();
		}
	}

	for (int32 Index = 0; Index < PodiumFaces.Num(); ++Index)
	{
		if (!View.Teams.IsValidIndex(Index) || !PodiumFaces[Index])
		{
			continue;
		}
		const FTJTeam& Team = View.Teams[Index];
		if (UTJPodiumWidget* Widget = Cast<UTJPodiumWidget>(PodiumFaces[Index]->GetUserWidgetObject()))
		{
			Widget->Apply(Team.Name, Team.Score, Team.Color, Index == View.BuzzedTeamIndex);
			PodiumFaces[Index]->RequestRedraw();
		}
		if (PodiumMaterials.IsValidIndex(Index) && PodiumMaterials[Index])
		{
			const FLinearColor Color = Index == View.BuzzedTeamIndex
				? FMath::Lerp(Team.Color, FLinearColor::White, 0.25f)
				: Team.Color;
			PodiumMaterials[Index]->SetVectorParameterValue(TEXT("Color"), Color);
		}
	}
}

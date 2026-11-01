#include "TJAudiencePawn.h"

#include "EngineUtils.h"
#include "TJStudio.h"

ATJAudiencePawn::ATJAudiencePawn()
{
	PrimaryActorTick.bCanEverTick = true;
	AutoPossessPlayer = EAutoReceiveInput::Disabled;

	Camera = CreateDefaultSubobject<UCameraComponent>(TEXT("Camera"));
	SetRootComponent(Camera);
	Camera->SetFieldOfView(60.f);
}

void ATJAudiencePawn::Tick(float DeltaSeconds)
{
	Super::Tick(DeltaSeconds);
	if (!GetWorld())
	{
		return;
	}

	for (TActorIterator<ATJStudio> It(GetWorld()); It; ++It)
	{
		if (!It->HasBoard())
		{
			continue;
		}
		const FVector Target = It->GetBoardCenter();
		const FVector Eye = Target + FVector(1500.f, 0.f, 50.f);
		SetActorLocationAndRotation(Eye, (Target - Eye).Rotation());
		SetActorTickEnabled(false);
		return;
	}
}
